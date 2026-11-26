// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
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
import kotlin.coroutines.cancellation.CancellationException
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition

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
     * Looks up the definition at [position] as [Location]s. An empty list means nothing is there (no symbol under
     * the cursor, no definition with a position, a file that is not compiled); a lookup that broke is
     * [Result.failure] with its cause, never an empty list. Cancellation is rethrown.
     */
    internal suspend fun lookupDefinitions(
        uri: String,
        position: Position,
        inputs: DefinitionInputs? = null,
    ): Result<List<Location>> = lookup(uri, position, inputs, spockTarget = { it }, render = {
        locationOf(it.result, it.context, it.documentUri)
    })

    /** Like [lookupDefinitions], as [LocationLink]s, resolving the position once. */
    internal suspend fun lookupDefinitionLinks(
        uri: String,
        position: Position,
        inputs: DefinitionInputs? = null,
    ): Result<List<LocationLink>> = lookup(
        uri,
        position,
        inputs,
        spockTarget = { LocationLink(it.uri, it.range, it.range) },
        render = {
            val originNode = it.context.visitor.getNodeAt(it.documentUri, it.position)
            createLocationLink(it.result, originNode, it.context, it.documentUri, position)
        },
    )

    /** A lookup's result with the one telemetry event that describes it. */
    private class Verdict<out T>(val event: DefinitionTelemetryEvent, val targets: Result<List<T>>)

    /** The resolved definition and what rendering it needs. */
    private class Resolved(
        val result: DefinitionResolver.DefinitionResult,
        val context: DefinitionContext,
        val documentUri: URI,
        val position: GroovyPosition,
    )

    /**
     * The single lookup behind every definition request: exactly one telemetry event per request, reported after
     * the lookup's own try so a throwing sink can neither add a second event nor be swallowed as a lookup failure.
     * An unexpected failure is the [Result.failure]; cancellation is reported and rethrown.
     */
    private suspend fun <T> lookup(
        uri: String,
        position: Position,
        inputs: DefinitionInputs?,
        spockTarget: (Location) -> T,
        render: suspend (Resolved) -> T,
    ): Result<List<T>> {
        val documentUri = parseUriOrReport(uri) ?: return Result.success(emptyList())
        val attempt = try {
            runSuspendCatching { attemptLookup(documentUri, position, inputs, spockTarget, render) }
        } catch (e: CancellationException) {
            logger.debug { "Definition resolution cancelled by client for: $uri" }
            telemetrySink.report(
                DefinitionTelemetryEvent(uri, DefinitionStatus.CANCELLED, "Request cancelled by client"),
            )
            throw e
        }
        val verdict = attempt.getOrElse { error ->
            // Not logged here: the response boundary logs a failed request once, with its stack.
            Verdict(DefinitionTelemetryEvent(uri, DefinitionStatus.ERROR, failureReason(error)), Result.failure(error))
        }
        telemetrySink.report(verdict.event)
        return verdict.targets
    }

    private suspend fun <T> attemptLookup(
        documentUri: URI,
        position: Position,
        inputs: DefinitionInputs?,
        spockTarget: (Location) -> T,
        render: suspend (Resolved) -> T,
    ): Verdict<T> {
        val uri = documentUri.toString()
        // One snapshot supplies text, AST and symbols, so a concurrent recompile cannot mix generations.
        val snapshot = inputs?.origin ?: compilationService.getCompiledSnapshot(documentUri)
        spockDefinition(documentUri, position, snapshot)?.let {
            return Verdict(
                DefinitionTelemetryEvent(uri, DefinitionStatus.SUCCESS),
                Result.success(listOf(spockTarget(it))),
            )
        }
        if (snapshot == null || snapshot.parseResult.ast == null) {
            logger.warn { "No AST available for $uri - this might indicate compilation service cache issue" }
            return Verdict(DefinitionTelemetryEvent(uri, DefinitionStatus.AST_MISSING), Result.success(emptyList()))
        }
        val context = DefinitionContext(
            snapshot.parseResult.astModel,
            snapshot.parseResult.symbolTable,
            snapshot.sourceText,
            inputs,
        )
        val groovyPosition = position.toGroovyPosition(context.sourceText)
        val resolver = resolverFor(context)

        return when (val lookup = resolver.findDefinitionAt(documentUri, groovyPosition)) {
            is DefinitionResolver.DefinitionLookup.Found -> {
                val target = render(Resolved(lookup.result, context, documentUri, groovyPosition))
                Verdict(
                    DefinitionTelemetryEvent(uri, DefinitionStatus.SUCCESS),
                    Result.success(listOf(target)),
                )
            }

            is DefinitionResolver.DefinitionLookup.NotFound -> {
                logger.debug { "No definition found at position: ${lookup.error.reason}" }
                Verdict(DefinitionTelemetryEvent(uri, DefinitionStatus.NO_DEFINITION), Result.success(emptyList()))
            }

            is DefinitionResolver.DefinitionLookup.Failed -> {
                val reason = failureReason(lookup.error)
                logger.debug { "Definition resolution failed: $reason" }
                Verdict(
                    DefinitionTelemetryEvent(uri, DefinitionStatus.RESOLUTION_FAILED, reason),
                    Result.failure(lookup.error),
                )
            }
        }
    }

    private fun resolverFor(context: DefinitionContext) = DefinitionResolver(
        context.visitor,
        context.symbolTable,
        compilationService,
        sourceNavigator,
        compilationService.getWorkspaceSymbolIndex(),
        context.inputs,
    )

    private suspend fun createLocationLink(
        result: DefinitionResolver.DefinitionResult,
        originNode: ASTNode?,
        context: DefinitionContext,
        documentUri: URI,
        position: Position,
    ): LocationLink {
        val originSelectionRange = context.inputs?.origin?.importSelectionAt(position) ?: originNode?.let {
            computeOriginSelectionRange(it)?.toProtocolRange(context.sourceText) ?: EMPTY_RANGE
        }

        return when (result) {
            is DefinitionResolver.DefinitionResult.Source -> {
                val targetUri = context.inputs?.targetUri(result)?.toString()
                    ?: result.node.toLspLocation(context.visitor)?.uri ?: result.uri.toString()
                val targetRange = result.node.toProtocolRange(
                    context.inputs?.textOf(URI.create(targetUri))
                        ?: compilationService.compiledTextOf(targetUri, documentUri, context.sourceText),
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

        val resolver = resolverFor(DefinitionContext(visitor, symbolTable, snapshot.sourceText))
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

    private data class DefinitionContext(
        val visitor: GroovyAstModel,
        val symbolTable: SymbolTable,
        val sourceText: String,
        val inputs: DefinitionInputs? = null,
    )

    private suspend fun locationOf(
        result: DefinitionResolver.DefinitionResult,
        context: DefinitionContext,
        documentUri: URI,
    ): Location = when (result) {
        is DefinitionResolver.DefinitionResult.Source -> {
            // Use the visitor-derived URI when possible; fall back to result.uri for synthetic nodes
            // (e.g., Jenkins vars) that aren't in the visitor's AST.
            val targetUri = context.inputs?.targetUri(result)?.toString()
                ?: result.node.toLspLocation(context.visitor)?.uri ?: result.uri.toString()
            val range = result.node.toProtocolRange(
                context.inputs?.textOf(URI.create(targetUri))
                    ?: compilationService.compiledTextOf(targetUri, documentUri, context.sourceText),
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

private const val MAX_REASON_LENGTH = 200

/** The failure's kind (its class) with its message, capped, so telemetry and logs keep the real reason. */
private fun failureReason(error: Throwable): String {
    val reason = "${error::class.simpleName}: ${error.message ?: "no message"}"
    return if (reason.length <= MAX_REASON_LENGTH) reason else reason.take(MAX_REASON_LENGTH - 1) + "…"
}

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

private fun CompiledDocumentSnapshot.importSelectionAt(position: Position): Range? {
    val module = parseResult.ast ?: return null
    val imports = module.imports + module.staticImports.values + module.staticStarImports.values
    val annotations = parseResult.sourceAnnotations
    val node = annotations.importAt(imports, position.line, position.character) ?: return null
    return annotations.importSelectionRange(node)?.let { range ->
        Range(Position(range.start.line, range.start.character), Position(range.end.line, range.end.character))
    }
}
