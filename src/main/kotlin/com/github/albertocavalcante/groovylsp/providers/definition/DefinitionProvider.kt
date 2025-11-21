package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovylsp.converters.toLspLocationLink
import com.github.albertocavalcante.groovyparser.ast.AstVisitor
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.errors.GroovyLspException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Main provider for go-to-definition functionality.
 * Uses Flow pattern inspired by kotlin-lsp for clean async handling.
 */
class DefinitionProvider(
    private val compilationService: GroovyCompilationService,
    private val telemetrySink: DefinitionTelemetrySink = DefinitionTelemetrySink.NO_OP,
) {

    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)

    /**
     * Provide definitions for the symbol at the given position using Flow pattern.
     * Returns a Flow of Location objects.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideDefinitions(uri: String, position: Position): Flow<Location> = flow {
        logger.debug("Providing definitions for $uri at ${position.line}:${position.character}")

        val documentUri = parseUriOrReport(uri) ?: return@flow
        val context = obtainDefinitionContext(documentUri, uri) ?: return@flow

        emitDefinitions(uri, documentUri, position.toGroovyPosition(), context)
    }

    /**
     * Provide definitions as LocationLink objects for enhanced navigation.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideDefinitionLinks(uri: String, position: Position): Flow<LocationLink> = flow {
        logger.debug("Providing definition links for $uri at ${position.line}:${position.character}")

        val documentUri = parseUriOrReport(uri) ?: return@flow

        val ast = compilationService.getAst(documentUri) ?: return@flow
        val visitor = compilationService.getAstVisitor(documentUri) ?: return@flow
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return@flow

        val resolver = DefinitionResolver(visitor, symbolTable)

        // Find the origin node at the position
        val originNode = visitor.getNodeAt(documentUri, position.toGroovyPosition())
        if (originNode == null) {
            logger.debug("No origin node found at position")
            return@flow
        }

        // Find the definition
        try {
            val definitionNode = resolver.findDefinitionAt(documentUri, position.toGroovyPosition())
            if (definitionNode != null) {
                // Convert to LocationLink and emit
                val locationLink = originNode.toLspLocationLink(definitionNode, visitor)
                if (locationLink != null) {
                    logger.debug("Found definition link to ${locationLink.targetUri}:${locationLink.targetRange}")
                    emit(locationLink)
                } else {
                    logger.debug("Could not convert to LocationLink")
                }
            }
        } catch (e: GroovyLspException) {
            // Handle expected LSP exceptions by not emitting anything (empty result)
            logger.debug("Definition link resolution failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid arguments during definition link resolution", e)
        } catch (e: IllegalStateException) {
            logger.warn("Invalid state during definition link resolution", e)
        } catch (e: Exception) {
            logger.warn("Unexpected error during definition link resolution", e)
        }
    }

    /**
     * Find all targets at position for the given target kinds.
     * Based on kotlin-lsp's pattern.
     */
    fun findTargetsAt(uri: String, position: Position, targetKinds: Set<TargetKind>): Flow<Location> = flow {
        logger.debug("Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds")

        val documentUri = try {
            URI.create(uri)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid URI format: $uri", e)
            return@flow
        }

        val ast = compilationService.getAst(documentUri) ?: return@flow
        val visitor = compilationService.getAstVisitor(documentUri) ?: return@flow
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return@flow

        val resolver = DefinitionResolver(visitor, symbolTable)
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

    private data class DefinitionContext(val visitor: AstVisitor, val symbolTable: SymbolTable)

    @Suppress("ReturnCount")
    private fun obtainDefinitionContext(documentUri: URI, originalUri: String): DefinitionContext? {
        val ast = compilationService.getAst(documentUri)
        if (ast == null) {
            logger.warn("No AST available for $originalUri - this might indicate compilation service cache issue")
            telemetrySink.report(DefinitionTelemetryEvent(originalUri, DefinitionStatus.AST_MISSING))
            return null
        }

        val visitor = compilationService.getAstVisitor(documentUri)
        if (visitor == null) {
            logger.warn("No AST visitor available for $originalUri - this might indicate visitor cache issue")
            telemetrySink.report(DefinitionTelemetryEvent(originalUri, DefinitionStatus.VISITOR_MISSING))
            return null
        }

        val symbolTable = compilationService.getSymbolTable(documentUri)
        if (symbolTable == null) {
            logger.warn("No symbol table available for $originalUri - this might indicate symbol table cache issue")
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
        val resolver = DefinitionResolver(context.visitor, context.symbolTable)
        var definitionFound = false
        try {
            val definitionNode = resolver.findDefinitionAt(documentUri, position)

            if (definitionNode != null) {
                val location = definitionNode.toLspLocation(context.visitor)
                if (location != null) {
                    logger.debug(
                        "Found definition at ${location.uri}:${location.range} " +
                            "(node: ${definitionNode.javaClass.simpleName})",
                    )
                    telemetrySink.report(
                        DefinitionTelemetryEvent(
                            uri = uri,
                            status = DefinitionStatus.SUCCESS,
                        ),
                    )
                    definitionFound = true
                    emit(location)
                } else {
                    logger.debug("Could not convert definition node to location")
                }
            } else {
                logger.debug("No definition found at position")
            }
        } catch (e: GroovyLspException) {
            logger.debug("Definition resolution failed: ${e.message}")
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.RESOLUTION_FAILED,
                    reason = e.message,
                ),
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid arguments during definition resolution", e)
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.ERROR,
                    reason = e.message,
                ),
            )
        } catch (e: IllegalStateException) {
            logger.warn("Invalid state during definition resolution", e)
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.ERROR,
                    reason = e.message,
                ),
            )
        } catch (e: Exception) {
            logger.warn("Unexpected error during definition resolution", e)
            telemetrySink.report(
                DefinitionTelemetryEvent(
                    uri = uri,
                    status = DefinitionStatus.ERROR,
                    reason = e.message,
                ),
            )
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

    private fun parseUriOrReport(uri: String): URI? = try {
        val parsed = URI.create(uri)
        if (!parsed.isAbsolute) {
            logger.warn("URI is not absolute: $uri")
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
        logger.error("Invalid URI format: $uri", e)
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
    ERROR,
}

fun interface DefinitionTelemetrySink {
    fun report(event: DefinitionTelemetryEvent)

    companion object {
        val NO_OP: DefinitionTelemetrySink = DefinitionTelemetrySink { _ -> }
    }
}
