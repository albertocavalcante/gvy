// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SourceRequestSelection
import com.github.albertocavalcante.gvy.gls.providers.definition.resolution.getClassName
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.OpenBuffer
import com.github.albertocavalcante.gvy.gls.utils.SourcePathAdmission
import com.github.albertocavalcante.gvy.gls.utils.classifySourcePath
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.ast.ImportNode
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.nio.file.Path

/** Explicit source actions reuse native resolution; downloads require the action's affirmative policy. */
internal class SourceCommandService(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
    private val navigator: SourceNavigator,
    private val registry: ExternalSourceRegistry,
) {
    suspend fun openLookupSession(descriptor: SourceDocumentDescriptor): SourceBrowserLookupSession? {
        val uri = descriptor.context.documentUri ?: return null
        if (registry.isManaged(uri)) return null
        val originalBuffer = documents.getBuffer(uri)
        if (uri.scheme == "file" && !ordinaryFile(uri)) return null
        if (!sameBuffer(originalBuffer, documents.getBuffer(uri))) return null

        ensureCompiled(uri, originalBuffer)
        currentCoroutineContext().ensureActive()
        if (!sameBuffer(originalBuffer, documents.getBuffer(uri))) return null
        val coroutine = currentCoroutineContext()
        val session = compilation.captureSourceLookupSession(
            descriptor,
            checkCancelled = { coroutine.ensureActive() },
        ) ?: return null
        if (!sameBuffer(originalBuffer, documents.getBuffer(uri))) return null

        return SourceBrowserLookupSession(
            locateType = { binaryName ->
                currentCoroutineContext().ensureActive()
                val result = when (val selection = session.select(binaryName)) {
                    is SourceRequestSelection.Selected -> navigator.locate(
                        selection.request.symbol,
                        selection.request.context,
                    )
                    SourceRequestSelection.BinaryAbsent ->
                        SourceLookupResult.Unavailable(MissingSourceReason.BINARY_TYPE_NOT_PRESENT)
                    SourceRequestSelection.Ambiguous -> SourceLookupResult.Ambiguous
                    SourceRequestSelection.ContextUnavailable -> SourceLookupResult.Stale
                    is SourceRequestSelection.Failed -> SourceLookupResult.Failed(selection.failure)
                }
                currentCoroutineContext().ensureActive()
                if (session.isCurrent()) result else SourceLookupResult.Stale
            },
            isCurrent = session::isCurrent,
            validateCurrent = session::validateCurrent,
        )
    }

    suspend fun resolve(params: SourceResolveParams): SourceResolveResult = act(
        params.documentUri,
        params.position,
        params.documentVersion,
    ) { request ->
        val policy = if (params.allowDownload) {
            SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS
        } else {
            SourceLookupPolicy.LOCAL_ONLY
        }
        val context = SourceLookupContext(
            request.context.identity,
            policy,
            request.context::validateCurrent,
            request.context::isCurrent,
        )
        navigator.retrySource(request.symbol, context)
    }

    suspend fun attach(params: SourceAttachParams): SourceResolveResult {
        val source = try {
            URI.create(params.sourcesUri).takeIf { it.scheme == "file" }?.let(Path::of)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return invalid("Choose a local source archive.")
        if (params.expectedSha256 != null && !SHA256.matches(params.expectedSha256)) {
            return invalid("Expected source SHA-256 must contain 64 hexadecimal characters.")
        }
        return act(params.documentUri, params.position, params.documentVersion) { request ->
            navigator.attachSource(request.symbol, request.context, source, params.expectedSha256)
        }
    }

    private suspend fun act(
        rawUri: String,
        position: Position,
        version: Int?,
        lookup: suspend (ResolvedSourceRequest) -> SourceLookupResult,
    ): SourceResolveResult {
        val uri = try {
            URI.create(rawUri).takeIf { documentUri ->
                documentUri.scheme == "file" && !registry.isManaged(documentUri)
            }
        } catch (_: IllegalArgumentException) {
            null
        } ?: return invalid("Run source navigation from the original Groovy document.")
        val originalBuffer = documents.getBuffer(uri)
        if (version != null && originalBuffer?.snapshot?.version != version) return stale()
        if (!ordinaryFile(uri)) return invalid("Run source navigation from an ordinary Groovy document.")
        if (!sameBuffer(originalBuffer, documents.getBuffer(uri))) return stale()

        val coroutine = currentCoroutineContext()
        ensureCompiled(uri, originalBuffer)
        coroutine.ensureActive()
        if (!sameBuffer(originalBuffer, documents.getBuffer(uri))) return stale()
        val snapshot = compilation.getAdmittedCompiledSnapshot(uri) { coroutine.ensureActive() } ?: return stale()
        if (!sameBuffer(originalBuffer, snapshot.buffer)) return stale()
        if (version != null && snapshot.buffer?.snapshot?.version != version) return stale()
        val symbol = symbolAt(uri, position, snapshot) ?: return SourceResolveResult(
            SourceResolveStatus.UNAVAILABLE,
            reason = "UNRESOLVED_SYMBOL",
            message = "No dependency type is resolved at this position.",
        )
        val request = compilation.captureSourceRequest(
            uri,
            symbol,
            checkCancelled = { coroutine.ensureActive() },
        ) ?: return SourceResolveResult(
            SourceResolveStatus.UNAVAILABLE,
            reason = "UNKNOWN_ORIGIN",
            message = "The selected dependency origin is unavailable.",
        )
        if (!compilation.isAdmittedCompiledSnapshotCurrent(uri, snapshot) { coroutine.ensureActive() }) return stale()
        val result = lookup(request)
        coroutine.ensureActive()
        if (
            !compilation.isAdmittedCompiledSnapshotCurrent(uri, snapshot) { coroutine.ensureActive() } ||
            !request.context.isCurrent()
        ) {
            return stale()
        }
        return response(result)
    }

    private suspend fun ensureCompiled(uri: URI, buffer: OpenBuffer?) {
        if (buffer == null) {
            compilation.ensureCompiled(uri)
        } else {
            compilation.ensureCompiled(uri, buffer.snapshot)
        }
    }

    private fun sameBuffer(captured: OpenBuffer?, current: OpenBuffer?): Boolean = when {
        captured == null -> current == null
        current == null -> false
        else -> captured.incarnation === current.incarnation && captured.snapshot === current.snapshot
    }

    private fun ordinaryFile(uri: URI): Boolean {
        val path = try {
            Path.of(uri)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return classifySourcePath(path) { registry.isManaged(it.toUri()) } == SourcePathAdmission.ORDINARY
    }

    private fun symbolAt(uri: URI, position: Position, snapshot: CompiledDocumentSnapshot): String? {
        if (position.line < 0 || position.character < 0) return null
        val annotations = snapshot.parseResult.sourceAnnotations
        val annotation = annotations.headAt(position.line, position.character)
        if (annotation != null) return if (annotation.sourceUnitName == null) annotation.binaryName else null
        if (annotations.isHeadNameTriviaAt(position.line, position.character)) return null
        val module = snapshot.parseResult.ast
        val import = module?.let { sourceModule ->
            annotations.importAt(
                sourceModule.imports + sourceModule.staticImports.values,
                position.line,
                position.character,
            )
        }
        if (import != null) return dependencyImportName(import, annotations)
        val parserPosition = com.github.albertocavalcante.groovyparser.ast.types.Position(
            position.line,
            position.character,
        )
        val node = snapshot.parseResult.astModel.getNodeAt(uri, parserPosition) ?: return null
        return if (node is ImportNode) dependencyImportName(node, annotations) else getClassName(node)
    }

    private fun dependencyImportName(import: ImportNode, annotations: SourceAnnotationIndex): String? {
        if (import.isStatic && import.fieldName != null) {
            val annotation = annotations.annotationImportedBy(import) ?: return null
            return if (annotation.sourceUnitName == null) annotation.binaryName else null
        }
        val selected = import.type?.redirect() ?: return null
        if (selected.isPrimaryClassNode || !selected.isResolved) return null
        return getClassName(import)
    }

    private fun response(result: SourceLookupResult): SourceResolveResult = when (result) {
        is SourceLookupResult.Located -> SourceResolveResult(
            SourceResolveStatus.LOCATED,
            listOf(
                LocationLink().apply {
                    targetUri = result.uri.toString()
                    targetRange = result.definition.declarationRange.toLsp()
                    targetSelectionRange = result.definition.nameRange.toLsp()
                },
            ),
        )
        is SourceLookupResult.FileOnly -> SourceResolveResult(
            SourceResolveStatus.FILE_ONLY,
            fileUri = result.uri.toString(),
            reason = result.reason.name,
            message = "Sources are available, but this declaration could not be located.",
        )
        is SourceLookupResult.Unavailable -> SourceResolveResult(
            SourceResolveStatus.UNAVAILABLE,
            reason = result.reason.name,
            message = when (result.reason) {
                MissingSourceReason.BINARY_ORIGIN_MISMATCH ->
                    "The repository JAR does not match the selected dependency. Attach matching sources."
                else -> "Source is unavailable for the selected dependency. Download or attach its sources."
            },
        )
        is SourceLookupResult.Failed -> SourceResolveResult(
            SourceResolveStatus.FAILED,
            reason = result.failure.name,
            message = "Dependency sources could not be verified or read.",
        )
        SourceLookupResult.Ambiguous -> SourceResolveResult(
            SourceResolveStatus.AMBIGUOUS,
            message = "The dependency source origin is ambiguous.",
        )
        SourceLookupResult.Stale -> stale()
    }

    private fun SourceSpan.toLsp() = Range(Position(startLine, startCharacter), Position(endLine, endCharacter))

    private fun stale() = SourceResolveResult(
        SourceResolveStatus.STALE,
        message = "The original document or dependency context changed. Run source navigation again.",
    )

    private fun invalid(message: String) = SourceResolveResult(
        SourceResolveStatus.FAILED,
        reason = "INVALID_REQUEST",
        message = message,
    )

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
