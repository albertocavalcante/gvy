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

        astModel.getAllNodes()
            .filter { it.hasValidPosition() }
            .forEach { node ->
                val nodeDefinition = node.resolveToDefinition(astModel, symbolTable, strict = false)

                val isMatch = when {
                    nodeDefinition == null -> false
                    nodeDefinition is Parameter && definition is Parameter ->
                        areParametersEqual(nodeDefinition, definition, node, definition, astModel)
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
                val isAssignment = parent.operation.text in listOf("=", "+=", "-=", "*=", "/=", "%=")
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
     */
    private fun areParametersEqual(
        p1: Parameter,
        p2: Parameter,
        n1: ASTNode,
        n2: ASTNode,
        astModel: GroovyAstModel,
    ): Boolean {
        if (p1.name != p2.name) return false

        // Check if they are in the same enclosing method/scope.
        // This is crucial because SymbolRegistry might return the same Parameter instance
        // for different methods due to not being scope-aware.
        val method1 = findEnclosingMethod(n1, astModel)
        val method2 = findEnclosingMethod(n2, astModel)

        return method1 == method2
    }

    private fun findEnclosingMethod(node: ASTNode, astModel: GroovyAstModel): MethodNode? {
        var current: ASTNode? = node
        while (current != null && current !is MethodNode) {
            current = astModel.getParent(current)
        }
        return current as? MethodNode
    }
}
