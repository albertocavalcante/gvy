package com.github.albertocavalcante.groovylsp.providers.highlight

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspRange
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for document highlights - highlighting all occurrences of a symbol in a document.
 *
 * This is similar to references but limited to a single document and includes read/write semantics.
 */
class DocumentHighlightProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(DocumentHighlightProvider::class.java)

    /**
     * Find all highlights for the symbol at the given position.
     *
     * @param uri The URI of the document
     * @param position The position in the document
     * @return List of document highlights with read/write classification
     */
    fun provideHighlights(uri: String, position: Position): List<DocumentHighlight> {
        logger.debug("Finding highlights for $uri at ${position.line}:${position.character}")

        val documentUri = URI.create(uri)
        val groovyPosition = position.toGroovyPosition()

        // Get AST model
        val astModel = compilationService.getAstModel(documentUri)
        if (astModel == null) {
            logger.debug("No AST model available for $uri")
            return emptyList()
        }

        // Get symbol table for resolution
        val symbolTable = compilationService.getSymbolTable(documentUri)
        if (symbolTable == null) {
            logger.debug("No symbol table available for $uri")
            return emptyList()
        }

        // Find node at position
        val targetNode = astModel.getNodeAt(documentUri, groovyPosition)
        if (targetNode == null || !targetNode.isHighlightableSymbol()) {
            logger.debug("No highlightable symbol at position")
            return emptyList()
        }

        // Resolve to definition
        val definition = targetNode.resolveToDefinition(astModel, symbolTable, strict = false)
        if (definition == null) {
            logger.debug("Could not resolve definition for node")
            return emptyList()
        }

        // Find all occurrences in the same document
        return findHighlights(definition, astModel, symbolTable, documentUri)
    }

    /**
     * Find all highlights for the given definition node.
     */
    private fun findHighlights(
        definition: ASTNode,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable,
        documentUri: URI,
    ): List<DocumentHighlight> {
        val highlights = mutableListOf<DocumentHighlight>()
        val seen = mutableSetOf<String>()

        astModel.getNodes(documentUri)
            .filter { it.hasValidPosition() }
            .forEach { node ->
                val nodeDefinition = node.resolveToDefinition(astModel, symbolTable, strict = false)

                val isMatch = when {
                    nodeDefinition == null -> false
                    nodeDefinition is Parameter && definition is Parameter ->
                        areParametersEqual(nodeDefinition, definition, node, astModel)
                    nodeDefinition == definition -> true
                    else -> false
                }

                if (isMatch) {
                    // Skip DeclarationExpressions - we emit their inner VariableExpression
                    if (node is DeclarationExpression) return@forEach

                    val highlight = createHighlight(node, astModel, seen)
                    if (highlight != null) {
                        highlights.add(highlight)
                    }
                }
            }

        logger.debug("Found ${highlights.size} highlights")
        return highlights
    }

    /**
     * Create a DocumentHighlight for the given node.
     */
    private fun createHighlight(
        node: ASTNode,
        astModel: GroovyAstModel,
        seen: MutableSet<String>,
    ): DocumentHighlight? {
        val range = node.toLspRange() ?: return null
        val key = "${range.start.line}:${range.start.character}"

        // Skip duplicates
        if (!seen.add(key)) return null

        val kind = classifyHighlightKind(node, astModel)
        return DocumentHighlight(range, kind)
    }

    /**
     * Classify whether a node represents a read or write operation.
     */
    private fun classifyHighlightKind(node: ASTNode, astModel: GroovyAstModel): DocumentHighlightKind {
        // Increment/decrement operators are writes
        if (node is PostfixExpression || node is PrefixExpression) {
            return DocumentHighlightKind.Write
        }

        // Check if node is part of an assignment (write)
        if (node is VariableExpression) {
            val parent = astModel.getParent(node)

            // Postfix/prefix increment/decrement is a write
            if (parent is PostfixExpression || parent is PrefixExpression) {
                return DocumentHighlightKind.Write
            }

            // Declaration is a write
            if (parent is DeclarationExpression && parent.leftExpression == node) {
                return DocumentHighlightKind.Write
            }

            // Assignment to this variable is a write
            if (parent is BinaryExpression) {
                // Include all assignment operators: standard, arithmetic, bitwise, shift, and power
                val assignmentOperators = setOf(
                    "=", "+=", "-=", "*=", "/=", "%=", // Arithmetic
                    "&=", "|=", "^=", // Bitwise
                    "<<=", ">>=", ">>>=", // Shift
                    "**=", // Power
                )
                val isAssignment = parent.operation.text in assignmentOperators
                if (isAssignment && parent.leftExpression == node) {
                    return DocumentHighlightKind.Write
                }
            }
        }

        // Parameters are declarations (write)
        if (node is Parameter) {
            return DocumentHighlightKind.Write
        }

        // Default to read
        return DocumentHighlightKind.Read
    }

    /**
     * Check if this node has valid position information for LSP.
     */
    private fun ASTNode.hasValidPosition(): Boolean = lineNumber > 0 && columnNumber > 0

    /**
     * Check if this node represents a highlightable symbol.
     */
    private fun ASTNode.isHighlightableSymbol(): Boolean {
        val highlightableTypes = setOf(
            VariableExpression::class,
            DeclarationExpression::class,
            Parameter::class,
            Variable::class,
            MethodCallExpression::class,
            MethodNode::class,
            FieldNode::class,
            PropertyNode::class,
            PropertyExpression::class,
            ClassNode::class,
            ClassExpression::class,
            ConstructorCallExpression::class,
            PostfixExpression::class,
            PrefixExpression::class,
        )
        return highlightableTypes.contains(this::class)
    }

    /**
     * Compare two Parameters for equality, including scope check.
     * Uses the enclosing parameter scope (method or closure) to verify parameters are in the same scope.
     */
    private fun areParametersEqual(
        p1: Parameter,
        p2: Parameter,
        referenceNode: ASTNode,
        astModel: GroovyAstModel,
    ): Boolean {
        if (p1.name != p2.name) return false

        // Find the scope containing the reference (method or closure)
        val scope = findEnclosingParameterScope(referenceNode, astModel)
        if (scope == null) {
            logger.debug("Parameter scope check: scope not found for ${p1.name}")
            return false
        }

        // Get parameters from the scope (either MethodNode or ClosureExpression)
        val scopeParams = when (scope) {
            is MethodNode -> scope.parameters?.toList() ?: emptyList()
            is ClosureExpression -> scope.parameters?.toList() ?: emptyList()
            else -> emptyList()
        }

        // Check if both parameters belong to this scope
        val p1InScope = scopeParams.any { it.name == p1.name && isSameParameter(it, p1) }
        val p2InScope = scopeParams.any { it.name == p2.name && isSameParameter(it, p2) }

        return p1InScope && p2InScope
    }

    /**
     * Check if two Parameter instances refer to the same parameter.
     * Uses identity check first, then falls back to position comparison.
     * Returns false if we can't reliably determine equality (conservative approach).
     */
    private fun isSameParameter(p1: Parameter, p2: Parameter): Boolean {
        // Identity check - same object reference
        if (p1 === p2) return true
        // Position comparison if both have valid positions
        if (p1.lineNumber > 0 && p2.lineNumber > 0) {
            return p1.lineNumber == p2.lineNumber && p1.columnNumber == p2.columnNumber
        }
        // Can't reliably determine - return false to avoid cross-scope matches
        return false
    }

    /**
     * Find the enclosing scope that can have parameters (MethodNode or ClosureExpression).
     * Closures are checked first since they're more immediate scopes for closure parameters.
     */
    private fun findEnclosingParameterScope(node: ASTNode, astModel: GroovyAstModel): ASTNode? {
        var current: ASTNode? = node
        while (current != null) {
            if (current is ClosureExpression || current is MethodNode) {
                return current
            }
            current = astModel.getParent(current)
        }
        return null
    }
}
