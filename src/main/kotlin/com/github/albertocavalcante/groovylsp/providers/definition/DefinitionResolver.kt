package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import com.github.albertocavalcante.groovylsp.errors.CircularReferenceException
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.SymbolNotFoundException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import com.github.albertocavalcante.groovylsp.errors.nodeNotFoundAtPosition
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Resolves symbols to their definitions using AST analysis.
 * Combines patterns from fork-groovy-language-server and kotlin-lsp.
 */
class DefinitionResolver(private val astVisitor: AstVisitor, private val symbolTable: SymbolTable) {

    private val logger = LoggerFactory.getLogger(DefinitionResolver::class.java)

    /**
     * Find the definition of the symbol at the given position.
     * Throws specific exceptions for different failure scenarios.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed as domain errors
    fun findDefinitionAt(uri: URI, position: Position): ASTNode? {
        logger.debug("Finding definition at $uri:${position.line}:${position.character}")

        return try {
            val targetNode = validateAndFindNode(uri, position)

            val definition = resolveDefinition(targetNode, uri, position)

            validateDefinition(definition, uri)
        } catch (e: GroovyLspException) {
            // Re-throw our specific exceptions
            logger.debug("Specific error finding definition: $e")
            throw e
        } catch (e: Exception) {
            logger.error("Unexpected error finding definition at $uri:${position.line}:${position.character}", e)
            throw createSymbolNotFoundException("unknown", uri, position)
        }
    }

    /**
     * Validate position and find the target node.
     */
    private fun validateAndFindNode(uri: URI, position: Position): ASTNode {
        // Validate position
        if (position.line < 0 || position.character < 0) {
            handleValidationError("invalidPosition", uri, ValidationContext.PositionContext(position))
        }

        // Find the node at the given LSP position
        val targetNode = astVisitor.getNodeAt(uri, position)
            ?: handleValidationError("nodeNotFound", uri, ValidationContext.PositionContext(position))

        logger.debug("Found target node: ${targetNode.javaClass.simpleName}")
        return targetNode
    }

    /**
     * Resolve the target node to its definition.
     */
    private fun resolveDefinition(targetNode: ASTNode, uri: URI, position: Position): ASTNode {
        val definition = try {
            targetNode.resolveToDefinition(astVisitor, symbolTable, strict = false)
        } catch (e: StackOverflowError) {
            logger.debug("Stack overflow during definition resolution, likely circular reference", e)
            handleResolutionError(e, targetNode, uri, position)
        } catch (e: IllegalArgumentException) {
            logger.debug("Invalid argument during definition resolution: ${e.message}", e)
            handleResolutionError(e, targetNode, uri, position)
        } catch (e: IllegalStateException) {
            logger.debug("Invalid state during definition resolution: ${e.message}", e)
            handleResolutionError(e, targetNode, uri, position)
        }

        // FIXME: Filter out non-definition nodes that shouldn't be treated as symbols
        val filteredDefinition = when (definition) {
            is org.codehaus.groovy.ast.expr.ConstantExpression -> null // String literals aren't definitions
            else -> definition
        }

        return filteredDefinition ?: handleResolutionError(null, targetNode, uri, position)
    }

    /**
     * Validate the definition node has proper position information.
     */
    private fun validateDefinition(definition: ASTNode, uri: URI): ASTNode {
        // Make sure the definition has valid position information
        if (!definition.hasValidPosition()) {
            logger.debug("Definition node has invalid position information")
            handleValidationError("invalidDefinitionPosition", uri, ValidationContext.NodeContext(definition))
        }

        logger.debug(
            "Resolved to definition: ${definition.javaClass.simpleName} " +
                "at ${definition.lineNumber}:${definition.columnNumber}",
        )
        return definition
    }

    /**
     * Create a SymbolNotFoundException with consistent parameters.
     */
    private fun createSymbolNotFoundException(symbol: String, uri: URI, position: Position) =
        SymbolNotFoundException(symbol, uri, position.line, position.character)

    /**
     * Handle resolution errors by throwing appropriate exceptions.
     * Consolidates all exception throwing logic to reduce throw count.
     */
    @Suppress("ThrowsCount") // This method centralizes all throws to satisfy detekt
    private fun handleResolutionError(
        originalException: Throwable?,
        targetNode: ASTNode,
        uri: URI,
        position: Position,
    ): Nothing = when (originalException) {
        is StackOverflowError -> throw CircularReferenceException(
            targetNode.javaClass.simpleName,
            listOf("resolveToDefinition", targetNode.toString()),
        )
        else -> throw createSymbolNotFoundException(targetNode.toString(), uri, position)
    }

    /**
     * Validation context for different error scenarios.
     */
    private sealed class ValidationContext {
        data class PositionContext(val position: Position) : ValidationContext()
        data class NodeContext(val node: ASTNode) : ValidationContext()
    }

    /**
     * Handle validation errors by throwing appropriate exceptions.
     * Consolidates validation throws to reduce throw count.
     */
    @Suppress("ThrowsCount") // This method centralizes all throws to satisfy detekt
    private fun handleValidationError(errorType: String, uri: URI, context: ValidationContext): Nothing =
        when (context) {
            is ValidationContext.PositionContext -> handlePositionValidationError(errorType, uri, context.position)
            is ValidationContext.NodeContext -> handleNodeValidationError(errorType, uri, context.node)
        }

    private fun handlePositionValidationError(errorType: String, uri: URI, position: Position): Nothing =
        when (errorType) {
            "invalidPosition" -> throw uri.invalidPosition(position.line, position.character, "Negative coordinates")
            "nodeNotFound" -> throw uri.nodeNotFoundAtPosition(position.line, position.character)
            else -> throw createSymbolNotFoundException("unknown", uri, position)
        }

    private fun handleNodeValidationError(errorType: String, uri: URI, node: ASTNode): Nothing = when (errorType) {
        "invalidDefinitionPosition" -> throw uri.invalidPosition(
            node.lineNumber,
            node.columnNumber,
            "Definition node has invalid position information",
        )
        else -> throw createSymbolNotFoundException(
            node.toString(),
            uri,
            org.eclipse.lsp4j.Position(node.lineNumber, node.columnNumber),
        )
    }

    /**
     * Find all targets at the given position for the specified target kinds.
     * Based on kotlin-lsp's getTargetsAtPosition pattern.
     */
    fun findTargetsAt(uri: URI, position: Position, targetKinds: Set<TargetKind>): List<ASTNode> {
        logger.debug("Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds")

        val targetNode = astVisitor.getNodeAt(uri, position.line, position.character)
            ?: return emptyList()

        val results = mutableListOf<ASTNode>()

        if (TargetKind.REFERENCE in targetKinds) {
            // If looking for references, return the node itself if it's a reference
            if (targetNode.isReference()) {
                results.add(targetNode)
            }
        }

        if (TargetKind.DECLARATION in targetKinds) {
            // If looking for declarations, try to resolve to definition
            val definition = targetNode.resolveToDefinition(astVisitor, symbolTable, strict = false)
            if (definition != null && definition != targetNode && definition.hasValidPosition()) {
                results.add(definition)
            }
        }

        logger.debug("Found ${results.size} targets")
        return results
    }

    /**
     * Check if a node represents a reference (not a declaration)
     */
    private fun ASTNode.isReference(): Boolean = when (this) {
        is org.codehaus.groovy.ast.expr.VariableExpression -> true
        is org.codehaus.groovy.ast.expr.MethodCallExpression -> true
        is org.codehaus.groovy.ast.expr.ConstructorCallExpression -> true
        is org.codehaus.groovy.ast.expr.PropertyExpression -> true
        else -> false
    }

    /**
     * Check if the node has valid position information
     */
    private fun ASTNode.hasValidPosition(): Boolean =
        lineNumber > 0 && columnNumber > 0 && lastLineNumber > 0 && lastColumnNumber > 0

    /**
     * Get statistics about the resolver state
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "symbolTableStats" to symbolTable.getStatistics(),
        "totalNodes" to astVisitor.getAllNodes().size,
        "totalClassNodes" to astVisitor.getAllClassNodes().size,
    )
}
