package com.github.albertocavalcante.groovylsp.providers.references

import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.LocationConverter
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for finding references to symbols in Groovy code.
 */
class ReferenceProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(ReferenceProvider::class.java)

    /**
     * Find all references to the symbol at the given position.
     *
     * @param uri The URI of the document
     * @param position The position in the document
     * @param includeDeclaration Whether to include the declaration in the results
     * @return Flow of locations where the symbol is referenced
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideReferences(uri: String, position: Position, includeDeclaration: Boolean): Flow<Location> = channelFlow {
        logger.debug("Finding references for $uri at ${position.line}:${position.character}")

        try {
            val context = createReferenceContext(uri, position) ?: return@channelFlow
            val definition = resolveTargetDefinition(context) ?: return@channelFlow

            logger.debug("Found definition: ${definition.javaClass.simpleName}")
            findReferences(definition, context.visitor, context.symbolTable, includeDeclaration)
        } catch (e: GroovyLspException) {
            logger.error("LSP error finding references", e)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments for finding references", e)
        } catch (e: IllegalStateException) {
            logger.error("Invalid state while finding references", e)
        } catch (e: Exception) {
            logger.error("Unexpected error finding references", e)
        }
    }

    /**
     * Context for reference resolution.
     */
    private data class ReferenceContext(
        val documentUri: URI,
        val visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
        val symbolTable: com.github.albertocavalcante.groovylsp.ast.SymbolTable,
        val targetNode: ASTNode,
    )

    /**
     * Parameters for processing a node reference.
     */
    private data class ProcessNodeParams(
        val node: ASTNode,
        val definition: ASTNode,
        val visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
        val symbolTable: com.github.albertocavalcante.groovylsp.ast.SymbolTable,
        val includeDeclaration: Boolean,
        val emittedLocations: MutableSet<String>,
    )

    /**
     * Create reference context from URI and position.
     */
    private fun createReferenceContext(uri: String, position: Position): ReferenceContext? =
        createReferenceContextInternal(uri, position) ?: logAndReturnNull("No referenceable node found at position")

    private fun createReferenceContextInternal(uri: String, position: Position): ReferenceContext? {
        val documentUri = URI.create(uri)
        return compilationService.getAstVisitor(documentUri)?.let { visitor ->
            compilationService.getSymbolTable(documentUri)?.let { symbolTable ->
                visitor.getNodeAt(documentUri, position.line, position.character)
                    ?.takeIf { it.isReferenceableSymbol() }
                    ?.let { ReferenceContext(documentUri, visitor, symbolTable, it) }
            }
        }
    }

    /**
     * Helper to log debug message and return null.
     */
    private fun logAndReturnNull(message: String): ReferenceContext? {
        logger.debug(message)
        return null
    }

    /**
     * Resolve the target node to its definition.
     */
    private fun resolveTargetDefinition(context: ReferenceContext): ASTNode? {
        // INSIGHT from fork-groovy-language-server: Get definition directly from the target node
        // For VariableExpression, this should return accessedVariable which unifies all references
        val definition = context.targetNode.resolveToDefinition(context.visitor, context.symbolTable, strict = false)

        // CRITICAL FIX: If position finder returned ClassNode but we have a VariableExpression nearby,
        // we might need to find the actual VariableExpression we're looking for
        val adjustedDefinition = when {
            // If we get a ClassNode but the context suggests we're looking for a variable
            context.targetNode is org.codehaus.groovy.ast.ClassNode &&
                definition is org.codehaus.groovy.ast.ClassNode -> {
                // Try to find a VariableExpression at the same position that might be the real target
                val targetLineNumber = context.targetNode.lineNumber
                val targetColumnNumber = context.targetNode.columnNumber

                if (targetLineNumber > 0 && targetColumnNumber > 0) {
                    // Look for VariableExpression nodes at or near this position
                    val allNodes = context.visitor.getAllNodes()
                    val variableAtPosition = allNodes
                        .filterIsInstance<org.codehaus.groovy.ast.expr.VariableExpression>()
                        .find { varNode ->
                            varNode.lineNumber > 0 && varNode.columnNumber > 0 &&
                                varNode.lineNumber == targetLineNumber &&
                                kotlin.math.abs(varNode.columnNumber - targetColumnNumber) <= 2
                        }

                    // If we found a VariableExpression, use its definition instead
                    variableAtPosition?.resolveToDefinition(
                        context.visitor,
                        context.symbolTable,
                        strict = false,
                    ) ?: definition
                } else {
                    definition
                }
            }
            else -> definition
        }

        if (adjustedDefinition == null) {
            logger.debug("Could not resolve definition for node")
            return null
        }
        return adjustedDefinition
    }

    /**
     * Find all references to the given definition node.
     */
    private suspend fun ProducerScope<Location>.findReferences(
        definition: ASTNode,
        visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
        symbolTable: com.github.albertocavalcante.groovylsp.ast.SymbolTable,
        includeDeclaration: Boolean,
    ) {
        val emittedLocations = mutableSetOf<String>()

        visitor.getAllNodes()
            .filter { it.hasValidPosition() }
            .forEach { node ->
                val params = ProcessNodeParams(
                    node = node,
                    definition = definition,
                    visitor = visitor,
                    symbolTable = symbolTable,
                    includeDeclaration = includeDeclaration,
                    emittedLocations = emittedLocations,
                )
                processNode(params)
            }
    }

    /**
     * Process a single node to check if it references our target definition.
     *
     * CRITICAL GROOVY AST INSIGHT: Groovy creates different VariableExpression objects for each
     * reference to the same variable, but they all share the same `accessedVariable` property
     * that points to the original declaration. This means we cannot use simple object identity
     * comparison (===) for VariableExpressions - we must compare their accessedVariable instead.
     *
     * Example: For code "def x = 1; x + 2; x * 3"
     * - Declaration VariableExpression: accessedVariable points to itself
     * - First reference: accessedVariable points to declaration
     * - Second reference: accessedVariable points to declaration
     * All three are different objects but share the same accessedVariable reference!
     */
    private suspend fun ProducerScope<Location>.processNode(params: ProcessNodeParams) {
        val nodeDefinition = params.node.resolveToDefinition(params.visitor, params.symbolTable, strict = false)

        // Simple and robust matching using .equals() like fork-groovy-language-server
        // INSIGHT: By returning accessedVariable as definition for VariableExpression,
        // all references naturally resolve to the same definition, making comparison simple

        val isMatchingDefinition = when {
            nodeDefinition != null -> nodeDefinition.equals(params.definition)
            else -> false
        }

        if (!isMatchingDefinition) return

        // CRITICAL FIX: Don't emit DeclarationExpressions as references - only their inner VariableExpression
        // This prevents double-counting when we have both "def localVar" (DeclarationExpression) and
        // "localVar" (VariableExpression) matching the same definition
        if (params.node is org.codehaus.groovy.ast.expr.DeclarationExpression) {
            return // Skip DeclarationExpression - we'll emit its VariableExpression separately
        }

        val isPartOfDeclaration = params.node.isPartOfDeclaration(params.visitor)
        logger.debug(
            "Node ${params.node.javaClass.simpleName} at ${params.node.lineNumber}:${params.node.columnNumber} - " +
                "isPartOfDeclaration: $isPartOfDeclaration, includeDeclaration: ${params.includeDeclaration}",
        )

        if (params.includeDeclaration || !isPartOfDeclaration) {
            emitUniqueLocation(params.node, params.visitor, params.emittedLocations)
        }
    }

    /**
     * Check if a node is part of a declaration.
     */
    private fun ASTNode.isPartOfDeclaration(visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor): Boolean =
        when {
            this is org.codehaus.groovy.ast.Parameter -> true
            this is org.codehaus.groovy.ast.MethodNode -> true
            this is org.codehaus.groovy.ast.FieldNode -> true
            this is org.codehaus.groovy.ast.PropertyNode -> true
            this is org.codehaus.groovy.ast.ClassNode -> true
            this is org.codehaus.groovy.ast.expr.VariableExpression -> {
                val parent = visitor.getParent(this)
                val isDecl = parent is org.codehaus.groovy.ast.expr.DeclarationExpression &&
                    parent.leftExpression == this
                if (isDecl) {
                    logger.debug("Found variable ${this.name} as part of declaration")
                }
                isDecl
            }
            else -> false
        }

    /**
     * Check if this node has valid position information for LSP.
     */
    private fun ASTNode.hasValidPosition(): Boolean = lineNumber > 0 && columnNumber > 0

    /**
     * Check if this node represents a referenceable symbol.
     * Groups related node types to reduce cyclomatic complexity.
     */
    private fun ASTNode.isReferenceableSymbol(): Boolean {
        val referenceableTypes = setOf(
            // Variable-related nodes
            org.codehaus.groovy.ast.expr.VariableExpression::class,
            org.codehaus.groovy.ast.expr.DeclarationExpression::class,
            org.codehaus.groovy.ast.Parameter::class,
            org.codehaus.groovy.ast.Variable::class,
            // Method and field nodes
            org.codehaus.groovy.ast.expr.MethodCallExpression::class,
            org.codehaus.groovy.ast.MethodNode::class,
            org.codehaus.groovy.ast.FieldNode::class,
            org.codehaus.groovy.ast.PropertyNode::class,
            org.codehaus.groovy.ast.expr.PropertyExpression::class,
            // Class-related nodes
            org.codehaus.groovy.ast.ClassNode::class,
            org.codehaus.groovy.ast.expr.ClassExpression::class,
            org.codehaus.groovy.ast.expr.ConstructorCallExpression::class,
            // Import nodes
            org.codehaus.groovy.ast.ImportNode::class,
        )
        return referenceableTypes.contains(this::class)
    }

    /**
     * Emit a location for this node if it hasn't been seen before.
     */
    private suspend fun ProducerScope<Location>.emitUniqueLocation(
        node: ASTNode,
        visitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
        seen: MutableSet<String>,
    ) {
        val location = LocationConverter.nodeToLocation(node, visitor) ?: return
        val key = "${location.uri}:${location.range.start.line}:${location.range.start.character}"
        if (seen.add(key)) {
            send(location)
        }
    }
}
