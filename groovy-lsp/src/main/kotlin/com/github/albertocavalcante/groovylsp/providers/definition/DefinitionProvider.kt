@file:Suppress("SwallowedException") // CancellationException is expected client behavior, logged at debug level

package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovylsp.converters.toLspRange
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.concurrent.CancellationException

/**
 * Main provider for go-to-definition functionality.
 * Uses Flow pattern inspired by kotlin-lsp for clean async handling.
 */
class DefinitionProvider(
    private val compilationService: GroovyCompilationService,
    private val sourceNavigator: SourceNavigator? = null,
    private val telemetrySink: DefinitionTelemetrySink = DefinitionTelemetrySink.NO_OP,
) {

    companion object {
        /** Default empty range for synthetic nodes without position info */
        private val EMPTY_RANGE = Range(Position(0, 0), Position(0, 0))
    }

    private val logger = KotlinLogging.logger {}

    /**
     * Provide definitions for the symbol at the given position using Flow pattern.
     * Returns a Flow of Location objects.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideDefinitions(uri: String, position: Position): Flow<Location> = flow {
        logger.debug { "Providing definitions for $uri at ${position.line}:${position.character}" }

        val documentUri = parseUriOrReport(uri) ?: return@flow
        val context = obtainDefinitionContext(documentUri, uri) ?: return@flow

        emitDefinitions(uri, documentUri, position.toGroovyPosition(), context)
    }

    /**
     * Provide definitions as LocationLink objects for enhanced navigation.
     */
    fun provideDefinitionLinks(uri: String, position: Position): Flow<LocationLink> = flow {
        logger.debug { "Providing definition links for $uri at ${position.line}:${position.character}" }

        val documentUri = parseUriOrReport(uri) ?: return@flow
        val context = obtainDefinitionContext(documentUri, uri) ?: return@flow

        val resolver = DefinitionResolver(
            context.visitor,
            context.symbolTable,
            compilationService,
            sourceNavigator,
            compilationService.getWorkspaceSymbolIndex(),
        )

        // Find the origin node at the position
        val originNode = context.visitor.getNodeAt(documentUri, position.toGroovyPosition())
        if (originNode == null) {
            logger.debug { "No origin node found at position" }
            return@flow
        }

        val locationLink = resolveDefinitionLink(resolver, documentUri, position, originNode, context.visitor)
        if (locationLink != null) {
            emit(locationLink)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Catch-all serves as final fallback for unexpected errors
    private suspend fun resolveDefinitionLink(
        resolver: DefinitionResolver,
        documentUri: URI,
        position: Position,
        originNode: ASTNode,
        visitor: GroovyAstModel,
    ): LocationLink? = try {
        val result = resolver.findDefinitionAt(documentUri, position.toGroovyPosition())
        result?.let { createLocationLink(it, originNode, visitor) }
    } catch (e: Exception) {
        when (e) {
            is CancellationException -> {
                logger.debug { "Definition link resolution cancelled by client" }
                throw e // Must re-throw to preserve coroutine cancellation
            }
            is GroovyLspException -> logger.debug { "Definition link resolution failed: ${e.message}" }
            is IllegalArgumentException -> logger.warn(e) { "Invalid arguments during definition link resolution" }
            is IllegalStateException -> logger.warn(e) { "Invalid state during definition link resolution" }
            else -> logger.warn(e) { "Unexpected error during definition link resolution" }
        }
        null
    }

    private fun createLocationLink(
        result: DefinitionResolver.DefinitionResult,
        originNode: ASTNode,
        visitor: GroovyAstModel,
    ): LocationLink {
        val originSelectionRange = computeOriginSelectionRange(originNode) ?: EMPTY_RANGE

        return when (result) {
            is DefinitionResolver.DefinitionResult.Source -> {
                val targetUri = result.node.toLspLocation(visitor)?.uri ?: result.uri.toString()
                val targetRange = result.node.toLspRange() ?: EMPTY_RANGE
                LocationLink().apply {
                    this.targetUri = targetUri
                    this.targetRange = targetRange
                    this.targetSelectionRange = targetRange
                    this.originSelectionRange = originSelectionRange
                }.also {
                    logger.debug { "Found definition link to ${it.targetUri}:${it.targetRange}" }
                }
            }
            is DefinitionResolver.DefinitionResult.Binary -> {
                val range = result.range ?: EMPTY_RANGE
                LocationLink().apply {
                    this.targetUri = result.uri.toString()
                    this.targetRange = range
                    this.targetSelectionRange = range
                    this.originSelectionRange = originSelectionRange
                }.also {
                    logger.debug { "Found binary definition link to ${it.targetUri}" }
                }
            }
        }
    }

    private fun computeOriginSelectionRange(originNode: ASTNode): Range? = when (originNode) {
        is ImportNode -> computeImportSelectionRange(originNode) ?: originNode.toLspRange()
        is MethodCallExpression -> originNode.method.toLspRange() ?: originNode.toLspRange()
        is PropertyExpression -> originNode.property.toLspRange() ?: originNode.toLspRange()
        else -> originNode.toLspRange()
    }

    private fun computeImportSelectionRange(importNode: ImportNode): Range? {
        val line = (importNode.lineNumber - 1).takeIf { it >= 0 } ?: return null
        val text = importNode.text ?: return null

        val symbol = importNode.fieldName
            ?: importNode.className?.substringAfterLast('.')
            ?: importNode.type?.name?.substringAfterLast('.')
            ?: return null

        // Find the symbol in the import text
        // Use lastIndexOf to prefer the rightmost occurrence (for cases like com.Foo.Bar.Bar)
        val symbolOffset = text.lastIndexOf(symbol)
        if (symbolOffset < 0) return null

        // ImportNode.text appears to be the full import line starting from column 0
        // so we can use symbolOffset directly as the column position
        val start = symbolOffset
        val end = start + symbol.length
        return Range(Position(line, start), Position(line, end))
    }

    /**
     * Find all targets at position for the given target kinds.
     * Based on kotlin-lsp's pattern.
     */
    fun findTargetsAt(uri: String, position: Position, targetKinds: Set<TargetKind>): Flow<Location> = flow {
        logger.debug { "Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds" }

        val documentUri = try {
            URI.create(uri)
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid URI format: $uri" }
            return@flow
        }

        val ast = compilationService.getAst(documentUri) ?: return@flow
        val visitor = compilationService.getAstModel(documentUri) ?: return@flow
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return@flow

        val resolver = DefinitionResolver(
            visitor,
            symbolTable,
            compilationService,
            sourceNavigator,
            compilationService.getWorkspaceSymbolIndex(),
        )
        val targets = resolver.findTargetsAt(documentUri, position.toGroovyPosition(), targetKinds)

        // Convert each target to Location and emit
        targets.forEach { targetNode ->
            val location = targetNode.toLspLocation(visitor)
            if (location != null) {
                emit(location)
            }
        }
    }

    /**
     * Convenience method for finding definitions only.
     */
    fun findDefinitionsOnly(uri: String, position: Position): Flow<Location> =
        findTargetsAt(uri, position, setOf(TargetKind.DECLARATION))

    /**
     * Convenience method for finding references only.
     */
    fun findReferencesOnly(uri: String, position: Position): Flow<Location> =
        findTargetsAt(uri, position, setOf(TargetKind.REFERENCE))

    private data class DefinitionContext(val visitor: GroovyAstModel, val symbolTable: SymbolTable)

    @Suppress("ReturnCount")
    private fun obtainDefinitionContext(documentUri: URI, originalUri: String): DefinitionContext? {
        val ast = compilationService.getAst(documentUri)
        if (ast == null) {
            logger.warn { "No AST available for $originalUri - this might indicate compilation service cache issue" }
            telemetrySink.report(DefinitionTelemetryEvent(originalUri, DefinitionStatus.AST_MISSING))
            return null
        }

        val visitor = compilationService.getAstModel(documentUri)
        if (visitor == null) {
            logger.warn { "No AST visitor available for $originalUri - this might indicate visitor cache issue" }
            telemetrySink.report(DefinitionTelemetryEvent(originalUri, DefinitionStatus.VISITOR_MISSING))
            return null
        }

        val symbolTable = compilationService.getSymbolTable(documentUri)
        if (symbolTable == null) {
            logger.warn { "No symbol table available for $originalUri - this might indicate symbol table cache issue" }
            telemetrySink.report(DefinitionTelemetryEvent(originalUri, DefinitionStatus.SYMBOL_TABLE_MISSING))
            return null
        }

        return DefinitionContext(visitor = visitor, symbolTable = symbolTable)
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private suspend fun FlowCollector<Location>.emitDefinitions(
        uri: String,
        documentUri: URI,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
        context: DefinitionContext,
    ) {
        val resolver = DefinitionResolver(
            context.visitor,
            context.symbolTable,
            compilationService,
            sourceNavigator,
            compilationService.getWorkspaceSymbolIndex(),
        )
        var definitionFound = false
        try {
            val result = resolver.findDefinitionAt(documentUri, position)

            if (result != null) {
                when (result) {
                    is DefinitionResolver.DefinitionResult.Source -> {
                        // Use the visitor-derived URI when possible; fall back to result.uri for synthetic nodes
                        // (e.g., Jenkins vars) that aren't in the visitor's AST.
                        val targetUri = result.node.toLspLocation(context.visitor)?.uri
                            ?: result.uri.toString()
                        val range = result.node.toLspRange()
                            ?: EMPTY_RANGE
                        val location = Location(targetUri, range)
                        logger.debug {
                            "Found definition at ${location.uri}:${location.range} " +
                                "(node: ${result.node.javaClass.simpleName})"
                        }
                        telemetrySink.report(
                            DefinitionTelemetryEvent(
                                uri = uri,
                                status = DefinitionStatus.SUCCESS,
                            ),
                        )
                        definitionFound = true
                        emit(location)
                    }

                    is DefinitionResolver.DefinitionResult.Binary -> {
                        val location =
                            Location(
                                result.uri.toString(),
                                result.range ?: EMPTY_RANGE,
                            )
                        logger.debug { "Found binary definition at ${location.uri}" }
                        telemetrySink.report(
                            DefinitionTelemetryEvent(
                                uri = uri,
                                status = DefinitionStatus.SUCCESS,
                            ),
                        )
                        definitionFound = true
                        emit(location)
                    }
                }
            } else {
                logger.debug { "No definition found at position" }
            }
        } catch (e: CancellationException) {
            // Client cancelled the request - must rethrow to preserve coroutine cancellation
            logger.debug { "Definition resolution cancelled by client for: $uri" }
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.CANCELLED,
                    reason = "Request cancelled by client",
                ),
            )
            throw e
        } catch (e: Exception) {
            handleResolutionException(e, uri)
        }

        if (!definitionFound) {
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.NO_DEFINITION,
                ),
            )
        }
    }

    /**
     * Handle exceptions from definition resolution with appropriate logging and telemetry.
     *
     * Maps exception types to their corresponding status codes:
     * - GroovyLspException → RESOLUTION_FAILED (debug log)
     * - IllegalArgumentException, IllegalStateException → ERROR (warn log)
     * - Other exceptions → ERROR (warn log)
     */
    private fun handleResolutionException(e: Exception, uri: String) {
        val (status, logLevel) = when (e) {
            is GroovyLspException -> DefinitionStatus.RESOLUTION_FAILED to "debug"
            is IllegalArgumentException, is IllegalStateException -> DefinitionStatus.ERROR to "warn"
            else -> DefinitionStatus.ERROR to "warn"
        }

        when (logLevel) {
            "debug" -> logger.debug { "Definition resolution failed: ${e.message}" }
            else -> logger.warn(e) { "Error during definition resolution: ${e.message}" }
        }

        telemetrySink.report(
            DefinitionTelemetryEvent(
                uri = uri,
                status = status,
                reason = e.message,
            ),
        )
    }

    private fun parseUriOrReport(uri: String): URI? = try {
        val parsed = URI.create(uri)
        if (!parsed.isAbsolute) {
            logger.warn { "URI is not absolute: $uri" }
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.INVALID_URI,
                    reason = "URI must be absolute",
                ),
            )
            null
        } else {
            parsed
        }
    } catch (e: IllegalArgumentException) {
        logger.error(e) { "Invalid URI format: $uri" }
        telemetrySink.report(
            DefinitionTelemetryEvent(
                uri = uri,
                status = DefinitionStatus.INVALID_URI,
                reason = e.message,
            ),
        )
        null
    }
}

data class DefinitionTelemetryEvent(val uri: String, val status: DefinitionStatus, val reason: String? = null)

enum class DefinitionStatus {
    SUCCESS,
    NO_DEFINITION,
    INVALID_URI,
    AST_MISSING,
    VISITOR_MISSING,
    SYMBOL_TABLE_MISSING,
    RESOLUTION_FAILED,
    CANCELLED,
    ERROR,
}

fun interface DefinitionTelemetrySink {
    fun report(event: DefinitionTelemetryEvent)

    companion object {
        val NO_OP: DefinitionTelemetrySink = DefinitionTelemetrySink { _ -> }
    }
}
