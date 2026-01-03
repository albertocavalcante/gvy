package com.github.albertocavalcante.groovylsp.providers.definition

import arrow.core.getOrElse
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.SymbolNotFoundException
import com.github.albertocavalcante.groovylsp.errors.invalidPosition
import com.github.albertocavalcante.groovylsp.errors.nodeNotFoundAtPosition
import com.github.albertocavalcante.groovylsp.providers.definition.resolution.ClasspathResolutionStrategy
import com.github.albertocavalcante.groovylsp.providers.definition.resolution.GlobalClassResolutionStrategy
import com.github.albertocavalcante.groovylsp.providers.definition.resolution.JenkinsVarsResolutionStrategy
import com.github.albertocavalcante.groovylsp.providers.definition.resolution.LocalSymbolResolutionStrategy
import com.github.albertocavalcante.groovylsp.providers.definition.resolution.ResolutionContext
import com.github.albertocavalcante.groovylsp.providers.definition.resolution.SymbolResolutionStrategy
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.slf4j.LoggerFactory
import java.net.URI

class DefinitionResolver(
    private val astVisitor: GroovyAstModel,
    private val symbolTable: SymbolTable,
    private val compilationService: GroovyCompilationService? = null,
    private val sourceNavigator: SourceNavigator? = null,
) {

    private val logger = LoggerFactory.getLogger(DefinitionResolver::class.java)

    /**
     * Resolution pipeline using Railway-Oriented Programming with Arrow Either.
     *
     * Strategies are tried in priority order:
     * 1. JenkinsVars - Jenkins vars/ directory lookup (highest priority)
     * 2. LocalSymbol - Same-file definitions via AST/symbol table
     * 3. GlobalClass - Cross-file class lookup via symbol index
     * 4. Classpath - JAR/JRT external dependencies (lowest priority)
     *
     * The pipeline short-circuits on first success (Either.Right).
     */
    private val resolutionPipeline: SymbolResolutionStrategy =
        run {
            val strategies = buildList {
                compilationService?.let { add(JenkinsVarsResolutionStrategy(it)) }
                add(LocalSymbolResolutionStrategy(astVisitor, symbolTable))
                compilationService?.let {
                    add(GlobalClassResolutionStrategy(it))
                    add(ClasspathResolutionStrategy(it, sourceNavigator))
                }
            }
            SymbolResolutionStrategy.pipeline(strategies)
        }

    /**
     * Find the definition of the symbol at the given position.
     * Throws specific exceptions for different failure scenarios.
     */

    /**
     * Result of a definition lookup.
     */
    sealed class DefinitionResult {
        data class Source(val node: ASTNode, val uri: URI) : DefinitionResult()
        data class Binary(val uri: URI, val name: String, val range: org.eclipse.lsp4j.Range? = null) :
            DefinitionResult()
    }

    /**
     * Find the definition of the symbol at the given position.
     * Throws specific exceptions for different failure scenarios.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed as domain errors
    suspend fun findDefinitionAt(
        uri: URI,
        position: Position,
    ): DefinitionResult? {
        logger.debug("Finding definition at $uri:${position.line}:${position.character}")

        return try {
            val targetNode = validateAndFindNode(uri, position)
            val result = resolveDefinition(targetNode, uri, position)

            if (result is DefinitionResult.Source) {
                validateDefinition(result.node, uri)
            }
            result
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
     * Orchestrates validation, node selection, and effective target resolution.
     */
    private fun validateAndFindNode(uri: URI, position: Position): ASTNode {
        validatePosition(position, uri)
        val trackedNode = astVisitor.getNodeAt(uri, position)
        val targetNode = selectBestNode(uri, position, trackedNode)
        return resolveEffectiveTarget(targetNode, trackedNode)
    }

    /**
     * Validate that position coordinates are non-negative.
     */
    private fun validatePosition(position: Position, uri: URI) {
        if (position.line < 0 || position.character < 0) {
            handleValidationError("invalidPosition", uri, ValidationContext.PositionContext(position))
        }
    }

    /**
     * Select the best node for the given position, using fallback if necessary.
     * Prefers more specific nodes over broad containers (ClassNode, BlockStatement, etc.)
     */
    private fun selectBestNode(uri: URI, position: Position, trackedNode: ASTNode?): ASTNode {
        val fallbackNode = (compilationService?.getAst(uri) as? ModuleNode)
            ?.findNodeAt(position.line, position.character)

        return when {
            trackedNode == null && fallbackNode != null -> fallbackNode
            shouldPreferFallback(trackedNode, fallbackNode) -> fallbackNode!!
            else -> trackedNode
        } ?: handleValidationError("nodeNotFound", uri, ValidationContext.PositionContext(position))
    }

    /**
     * Determine if the fallback node should be preferred over the tracked node.
     */
    private fun shouldPreferFallback(trackedNode: ASTNode?, fallbackNode: ASTNode?): Boolean =
        trackedNode != null && fallbackNode != null &&
            isBroadContainer(trackedNode) && !isBroadContainer(fallbackNode)

    /**
     * Resolve the effective target, preferring MethodCallExpression when applicable.
     * NOTE: Clicking on a method call can resolve to its ConstantExpression "method" node.
     * Prefer the enclosing MethodCallExpression to avoid false positives.
     */
    private fun resolveEffectiveTarget(targetNode: ASTNode, trackedNode: ASTNode?): ASTNode {
        val methodCall = extractMethodCallIfApplicable(targetNode, trackedNode)
        val effectiveTarget = methodCall ?: targetNode
        logger.debug("Found target node: ${effectiveTarget.javaClass.simpleName}")
        return effectiveTarget
    }

    /**
     * Extract MethodCallExpression if the target is a method name constant.
     */
    private fun extractMethodCallIfApplicable(targetNode: ASTNode, trackedNode: ASTNode?): MethodCallExpression? =
        when {
            targetNode is MethodCallExpression -> targetNode
            trackedNode is ConstantExpression -> findMethodCallWhereNodeIsMethodExpression(trackedNode)
            targetNode is ConstantExpression -> findMethodCallWhereNodeIsMethodExpression(targetNode)
            else -> null
        }

    private fun isBroadContainer(node: ASTNode): Boolean = node is ClassNode ||
        node is MethodNode ||
        node is BlockStatement ||
        node is Statement

    private fun findMethodCallWhereNodeIsMethodExpression(node: ASTNode): MethodCallExpression? {
        var current: ASTNode? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_SEARCH_DEPTH) {
            val parent = astVisitor.getParent(current)
            if (parent is MethodCallExpression && parent.method === current) {
                return parent
            }
            current = parent
            depth++
        }
        return null
    }

    companion object {
        private const val MAX_PARENT_SEARCH_DEPTH = 10
    }

    /**
     * Resolve the target node to its definition using the functional resolution pipeline.
     *
     * Uses Railway-Oriented Programming with Arrow Either for clean composition:
     * - Each strategy returns Either<ResolutionError, DefinitionResult>
     * - Pipeline short-circuits on first Right (success)
     * - Strategies are tried in priority order (Jenkins vars → Local → Global → Classpath)
     */
    private suspend fun resolveDefinition(targetNode: ASTNode, uri: URI, position: Position): DefinitionResult? {
        val context = ResolutionContext(
            targetNode = targetNode,
            documentUri = uri,
            position = position,
        )

        val result = resolutionPipeline.resolve(context)

        return result.getOrElse { error ->
            logger.debug("Resolution failed [{}]: {}", error.source, error.reason)
            null
        }
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
            Position(node.lineNumber, node.columnNumber),
        )
    }

    /**
     * Find all targets at the given position for the specified target kinds.
     * Based on kotlin-lsp's getTargetsAtPosition pattern.
     */
    fun findTargetsAt(uri: URI, position: Position, targetKinds: Set<TargetKind>): List<ASTNode> {
        logger.debug("Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds")

        val targetNode = astVisitor.getNodeAt(uri, position)
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
        is VariableExpression -> true
        is MethodCallExpression -> true
        is ConstructorCallExpression -> true
        is PropertyExpression -> true
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
