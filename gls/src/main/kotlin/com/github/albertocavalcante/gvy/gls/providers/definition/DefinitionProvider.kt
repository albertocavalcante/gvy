// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("SwallowedException") // CancellationException is expected client behavior, logged at debug level

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.compiledTextOf
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.converters.toProtocolRange
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
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
        // One snapshot supplies text, AST and symbols, so a concurrent recompile cannot mix generations.
        val snapshot = compilationService.getCompiledSnapshot(documentUri)
        spockDefinition(documentUri, position, snapshot)?.let {
            telemetrySink.report(DefinitionTelemetryEvent(uri, DefinitionStatus.SUCCESS))
            emit(it)
            return@flow
        }
        val context = obtainDefinitionContext(snapshot, uri) ?: return@flow

        emitDefinitions(uri, documentUri, position.toGroovyPosition(context.sourceText), context)
    }

    /**
     * Provide definitions as LocationLink objects for enhanced navigation.
     */
    fun provideDefinitionLinks(uri: String, position: Position): Flow<LocationLink> = flow {
        logger.debug { "Providing definition links for $uri at ${position.line}:${position.character}" }

        val documentUri = parseUriOrReport(uri) ?: return@flow
        val snapshot = compilationService.getCompiledSnapshot(documentUri)
        spockDefinition(documentUri, position, snapshot)?.let {
            emit(LocationLink(it.uri, it.range, it.range))
            return@flow
        }
        val context = obtainDefinitionContext(snapshot, uri) ?: return@flow
        val groovyPosition = position.toGroovyPosition(context.sourceText)

        val resolver = DefinitionResolver(
            context.visitor,
            context.symbolTable,
            compilationService,
            sourceNavigator,
            compilationService.getWorkspaceSymbolIndex(),
        )

        // Find the origin node at the position
        val originNode = context.visitor.getNodeAt(documentUri, groovyPosition)
        if (originNode == null) {
            logger.debug { "No origin node found at position" }
            return@flow
        }
        logger.debug {
            "originNode for selectionRange: ${originNode.javaClass.simpleName} at " +
                "${originNode.lineNumber}:${originNode.columnNumber}"
        }

        val locationLink = resolveDefinitionLink(resolver, documentUri, groovyPosition, originNode, context)
        if (locationLink != null) {
            emit(locationLink)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Catch-all serves as final fallback for unexpected errors
    private suspend fun resolveDefinitionLink(
        resolver: DefinitionResolver,
        documentUri: URI,
        groovyPosition: com.github.albertocavalcante.groovyparser.ast.types.Position,
        originNode: ASTNode,
        context: DefinitionContext,
    ): LocationLink? = try {
        when (val lookup = resolver.findDefinitionAt(documentUri, groovyPosition)) {
            is DefinitionResolver.DefinitionLookup.Found ->
                createLocationLink(lookup.result, originNode, context, documentUri)

            is DefinitionResolver.DefinitionLookup.NotFound -> null

            is DefinitionResolver.DefinitionLookup.Failed -> {
                logger.debug { "Definition link resolution failed: ${failureReason(lookup.error)}" }
                null
            }
        }
    } catch (e: Exception) {
        when (e) {
            is CancellationException -> {
                logger.debug { "Definition link resolution cancelled by client" }
                throw e // Must re-throw to preserve coroutine cancellation
            }
            is IllegalArgumentException -> logger.warn(e) { "Invalid arguments during definition link resolution" }
            is IllegalStateException -> logger.warn(e) { "Invalid state during definition link resolution" }
            else -> logger.warn(e) { "Unexpected error during definition link resolution" }
        }
        null
    }

    private fun createLocationLink(
        result: DefinitionResolver.DefinitionResult,
        originNode: ASTNode,
        context: DefinitionContext,
        documentUri: URI,
    ): LocationLink {
        val originSelectionRange = computeOriginSelectionRange(originNode)?.toProtocolRange(context.sourceText)
            ?: EMPTY_RANGE

        return when (result) {
            is DefinitionResolver.DefinitionResult.Source -> {
                val targetUri = result.node.toLspLocation(context.visitor)?.uri ?: result.uri.toString()
                val targetRange = result.node.toProtocolRange(
                    compilationService.compiledTextOf(targetUri, documentUri, context.sourceText),
                ) ?: EMPTY_RANGE
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

    private fun computeOriginSelectionRange(originNode: ASTNode): Range? {
        logger.debug { "computeOriginSelectionRange: ${originNode.javaClass.simpleName}" }
        return when (originNode) {
            is ImportNode -> computeImportSelectionRange(originNode) ?: originNode.toLspRange()
            is MethodCallExpression -> computeMethodCallSelectionRange(originNode) ?: originNode.toLspRange()
            is PropertyExpression -> computePropertySelectionRange(originNode) ?: originNode.toLspRange()
            is VariableExpression -> computeVariableSelectionRange(originNode) ?: originNode.toLspRange()
            is ConstantExpression -> computeConstantSelectionRange(originNode) ?: originNode.toLspRange()
            else -> originNode.toLspRange()
        }
    }

    /**
     * Compute the selection range for a method call expression, highlighting only the method name.
     * For `helper.registerMethod("test")`, returns the range of just `registerMethod`.
     */
    private fun computeMethodCallSelectionRange(node: MethodCallExpression): Range? {
        // First try: use method expression's position directly if valid
        node.method.toLspRange()?.let {
            logger.debug { "MethodCall: using method.toLspRange()" }
            return it
        }

        val methodName = node.methodAsString ?: return null
        val line = (node.lineNumber - 1).takeIf { it >= 0 } ?: return null

        // Second try: compute from objectExpression's end position
        // For `obj.method()`, the method starts right after the dot
        val objExpr = node.objectExpression
        if (objExpr != null && !node.isImplicitThis && objExpr.lastColumnNumber > 0) {
            // objExpr.lastColumnNumber is the 1-based exclusive end, so lastColumnNumber - 1 is the 0-based
            // column of '.'; method name starts one column later, i.e. at lastColumnNumber (0-based)
            val startCol = objExpr.lastColumnNumber
            logger.debug { "MethodCall: computed from objectExpression end ($methodName at col $startCol)" }
            return Range(Position(line, startCol), Position(line, startCol + methodName.length))
        }

        // Third try: use node text to find method name position
        val nodeStartCol = (node.columnNumber - 1).takeIf { it >= 0 } ?: return null
        val text = node.text
        if (text != null) {
            val methodOffset = text.lastIndexOf(methodName)
            if (methodOffset >= 0) {
                logger.debug { "MethodCall: computed from node text ($methodName at offset $methodOffset)" }
                return Range(
                    Position(line, nodeStartCol + methodOffset),
                    Position(line, nodeStartCol + methodOffset + methodName.length),
                )
            }
        }

        logger.debug { "MethodCall: could not compute range for $methodName" }
        return null
    }

    /**
     * Compute the selection range for a property expression, highlighting only the property name.
     * For `config.scriptRoot`, returns the range of just `scriptRoot`.
     */
    private fun computePropertySelectionRange(node: PropertyExpression): Range? {
        // First try: use property expression's position directly if valid
        node.property.toLspRange()?.let {
            logger.debug { "Property: using property.toLspRange()" }
            return it
        }

        val propertyName = node.propertyAsString ?: return null
        val line = (node.lineNumber - 1).takeIf { it >= 0 } ?: return null

        // Second try: compute from objectExpression's end position
        // For `obj.property`, the property starts right after the dot
        val objExpr = node.objectExpression
        if (objExpr != null && !node.isImplicitThis && objExpr.lastColumnNumber > 0) {
            // objExpr.lastColumnNumber is the 1-based exclusive end, so lastColumnNumber - 1 is the 0-based
            // column of '.'; property name starts one column later, i.e. at lastColumnNumber (0-based)
            val startCol = objExpr.lastColumnNumber
            logger.debug { "Property: computed from objectExpression end ($propertyName at col $startCol)" }
            return Range(Position(line, startCol), Position(line, startCol + propertyName.length))
        }

        // Third try: use node text to find property name position
        val nodeStartCol = (node.columnNumber - 1).takeIf { it >= 0 } ?: return null
        val text = node.text
        if (text != null) {
            val propertyOffset = text.lastIndexOf(propertyName)
            if (propertyOffset >= 0) {
                logger.debug { "Property: computed from node text ($propertyName at offset $propertyOffset)" }
                return Range(
                    Position(line, nodeStartCol + propertyOffset),
                    Position(line, nodeStartCol + propertyOffset + propertyName.length),
                )
            }
        }

        logger.debug { "Property: could not compute range for $propertyName" }
        return null
    }

    /**
     * Compute the selection range for a variable expression.
     * For `myVariable`, returns the range of exactly `myVariable`.
     */
    private fun computeVariableSelectionRange(node: VariableExpression): Range? {
        if (node.lineNumber < 0 || node.columnNumber < 0) return null
        val line = node.lineNumber - 1 // Convert to 0-based
        val col = node.columnNumber - 1 // Convert to 0-based
        val length = node.name.length
        logger.debug { "VariableExpression: ${node.name} at line $line, col $col, length $length" }
        return Range(Position(line, col), Position(line, col + length))
    }

    /**
     * Compute the selection range for a constant expression.
     * For string/number constants, returns the exact range.
     */
    private fun computeConstantSelectionRange(node: ConstantExpression): Range? {
        if (node.lineNumber < 0 || node.columnNumber < 0) return null
        val text = node.text ?: return null
        val line = node.lineNumber - 1
        val col = node.columnNumber - 1
        logger.debug { "ConstantExpression: '$text' at line $line, col $col" }
        return Range(Position(line, col), Position(line, col + text.length))
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

        // ImportNode.text is relative to the import node's position, so we need to add
        // the node's column offset to get the absolute column position in the source file.
        // This is consistent with GroovySemanticTokenProvider and UnusedImportDiagnosticProvider.
        val columnOffset = (importNode.columnNumber - 1).coerceAtLeast(0)
        val start = columnOffset + symbolOffset
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

        val snapshot = compilationService.getCompiledSnapshot(documentUri) ?: return@flow
        if (snapshot.parseResult.ast == null) return@flow
        val visitor = snapshot.parseResult.astModel
        val symbolTable = snapshot.parseResult.symbolTable

        val resolver = DefinitionResolver(
            visitor,
            symbolTable,
            compilationService,
            sourceNavigator,
            compilationService.getWorkspaceSymbolIndex(),
        )
        val groovyPosition = position.toGroovyPosition(snapshot.sourceText)
        val targets = resolver.findTargetsAt(documentUri, groovyPosition, targetKinds)

        // Convert each target to Location and emit
        targets.forEach { targetNode ->
            val location = targetNode.toLspLocation(visitor) {
                compilationService.compiledTextOf(it, documentUri, snapshot.sourceText)
            }
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

    private data class DefinitionContext(
        val visitor: GroovyAstModel,
        val symbolTable: SymbolTable,
        val sourceText: String,
    )

    private fun obtainDefinitionContext(snapshot: CompiledDocumentSnapshot?, originalUri: String): DefinitionContext? {
        if (snapshot == null || snapshot.parseResult.ast == null) {
            logger.warn { "No AST available for $originalUri - this might indicate compilation service cache issue" }
            telemetrySink.report(DefinitionTelemetryEvent(originalUri, DefinitionStatus.AST_MISSING))
            return null
        }
        return DefinitionContext(snapshot.parseResult.astModel, snapshot.parseResult.symbolTable, snapshot.sourceText)
    }

    @Suppress("TooGenericExceptionCaught") // Final fallback: an unexpected failure is reported, not rethrown
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
        // Exactly one telemetry event per request: the outcome, never a failure followed by "no definition".
        try {
            when (val lookup = resolver.findDefinitionAt(documentUri, position)) {
                is DefinitionResolver.DefinitionLookup.Found -> {
                    val location = locationOf(lookup.result, context, documentUri)
                    logger.debug { "Found definition at ${location.uri}:${location.range}" }
                    telemetrySink.report(DefinitionTelemetryEvent(uri = uri, status = DefinitionStatus.SUCCESS))
                    emit(location)
                }

                is DefinitionResolver.DefinitionLookup.NotFound -> {
                    logger.debug { "No definition found at position: ${lookup.reason}" }
                    telemetrySink.report(DefinitionTelemetryEvent(uri = uri, status = DefinitionStatus.NO_DEFINITION))
                }

                is DefinitionResolver.DefinitionLookup.Failed -> {
                    val reason = failureReason(lookup.error)
                    logger.debug { "Definition resolution failed: $reason" }
                    telemetrySink.report(
                        DefinitionTelemetryEvent(uri, DefinitionStatus.RESOLUTION_FAILED, reason),
                    )
                }
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
            logger.warn(e) { "Error during definition resolution: ${e.message}" }
            telemetrySink.report(DefinitionTelemetryEvent(uri, DefinitionStatus.ERROR, failureReason(e)))
        }
    }

    private fun locationOf(
        result: DefinitionResolver.DefinitionResult,
        context: DefinitionContext,
        documentUri: URI,
    ): Location = when (result) {
        is DefinitionResolver.DefinitionResult.Source -> {
            // Use the visitor-derived URI when possible; fall back to result.uri for synthetic nodes
            // (e.g., Jenkins vars) that aren't in the visitor's AST.
            val targetUri = result.node.toLspLocation(context.visitor)?.uri ?: result.uri.toString()
            val range = result.node.toProtocolRange(
                compilationService.compiledTextOf(targetUri, documentUri, context.sourceText),
            ) ?: EMPTY_RANGE
            Location(targetUri, range)
        }

        is DefinitionResolver.DefinitionResult.Binary -> Location(result.uri.toString(), result.range ?: EMPTY_RANGE)
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

/** The failure's kind (its class) with its message, so telemetry and logs keep the real reason. */
private fun failureReason(error: Throwable): String = "${error::class.simpleName}: ${error.message}"

private fun spockDefinition(uri: URI, position: Position, snapshot: CompiledDocumentSnapshot?): Location? {
    snapshot ?: return null
    val module = snapshot.parseResult.ast ?: return null
    val origin = snapshot.parseResult.astModel.getNodeAt(uri, position.toGroovyPosition(snapshot.sourceText))
    val isPartial = !snapshot.parseResult.isSuccessful
    val target = SpockDefinitionLookup.find(module, snapshot.sourceText, position, origin, isPartial) ?: return null
    return target.toProtocolRange(snapshot.sourceText)?.let { Location(uri.toString(), it) }
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
