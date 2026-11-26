// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.DocumentCompileContext
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.ReferenceReadiness
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.indexing.CapturedWorkspaceReferences
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.OpenDocuments
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.nativeapi.ParseMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Path

internal data class RenameRequest(
    val source: SnapshotSource,
    val position: Position,
    val buffers: OpenDocuments?,
    val extraFolders: List<Path>,
    val walkGeneration: Int,
    val readiness: ReferenceReadiness,
    val references: CapturedWorkspaceReferences,
    val context: DocumentCompileContext?,
)

/** Owns the source and compiler that supply one eager rename answer, including any inputs to a refusal. */
internal class RenameRequestQuery(
    private val compilationService: GroovyCompilationService,
    private val documentSource: RenameDocumentSource,
    private val caches: RenameCaches,
    private val scanLimits: ScanLimits,
    private val extraRoots: () -> List<Path>,
    private val documents: DocumentProvider?,
) {
    fun capture(uri: String, position: Position): RenameRequest {
        val source = compilationService.captureSnapshotSource(URI.create(uri))
        val readiness = compilationService.referenceReadiness(source)
        return RenameRequest(
            source,
            Position(position.line, position.character),
            documents?.captureDocuments(),
            extraRoots().toList(),
            caches.walks.invalidations,
            readiness,
            readiness.captureMembersView(),
            source.buffer?.let {
                compilationService.workspaceManager.compileContextFor(source.uri, it.snapshot.content, source.workspace)
                    .captureClasspath()
            },
        )
    }

    fun inputsCurrent(request: RenameRequest): Boolean {
        val source = request.source
        val sameContext = source.buffer?.let {
            compilationService.workspaceManager.compileContextFor(source.uri, it.snapshot.content, source.workspace)
        } == request.context
        return sameContext &&
            documents?.captureDocuments()?.identity === request.buffers?.identity &&
            extraRoots() == request.extraFolders &&
            source.workerCurrent() &&
            source.isCurrent()
    }

    suspend fun <T> query(
        request: RenameRequest,
        answer: suspend (CompiledDocumentSnapshot, RenameSourceSnapshots) -> T,
    ): T {
        val source = request.source
        val uri = source.uri
        val sources = RenameSourceSnapshots(documentSource, request)
        if (!inputsCurrent(request)) sources.changed(uri)
        // A direct provider may own an in-memory source outside the compilation service's document store.
        val supplied = if (documents == null) sources.capture(uri) else request.buffers?.documents?.get(uri)
        val compute: suspend (CompiledDocumentSnapshot) -> T = { compiled ->
            holdOriginal(request, supplied, compiled, sources)
            sources.guard = crossFileGuard(request, compiled, sources)
            val result = runSuspendCatching { answer(compiled, sources) }
            sources.validate()
            result.getOrThrow()
        }
        val result = runSuspendCatching {
            acquire(source, supplied, compute)
        }
        if (!inputsCurrent(request)) sources.changed(uri)
        sources.validatePublications()
        currentCoroutineContext().ensureActive()
        return when (val outcome = result.getOrThrow()) {
            is SnapshotQuery.Answer -> outcome.value
            SnapshotQuery.Missing -> throw ResponseErrorException(
                ResponseError(ResponseErrorCode.InvalidRequest, "Document not compiled: $uri", null),
            )
            SnapshotQuery.Stale -> sources.changed(uri)
            is SnapshotQuery.Failure -> throw outcome.cause
        }
    }

    private fun holdOriginal(
        request: RenameRequest,
        supplied: DocumentSnapshot?,
        compiled: CompiledDocumentSnapshot,
        sources: RenameSourceSnapshots,
    ) {
        val source = request.source
        val uri = source.uri
        val original = supplied ?: DocumentSnapshot(compiled.sourceText, null)
        if (original.content != compiled.sourceText ||
            request.context?.let { compiled.referenceProducer?.hasClasspath(it.classpath) != true } == true
        ) {
            sources.changed(uri)
        }
        if (documents != null && source.buffer != null && supplied == null) sources.changed(uri)
        sources.hold(uri, original)
    }

    private suspend fun <T> acquire(
        source: SnapshotSource,
        supplied: DocumentSnapshot?,
        compute: suspend (CompiledDocumentSnapshot) -> T,
    ): SnapshotQuery<T> {
        return if (source.buffer == null && supplied != null) {
            compilationService.queryDerivedSnapshot(
                source,
                supplied.content,
                mode = ParseMode.WORKSPACE,
                query = compute,
            )
        } else {
            when (val acquired = compilationService.acquireEditorSnapshot(source)) {
                is SnapshotAcquisition.Available -> acquired.query(compute)
                SnapshotAcquisition.Missing -> SnapshotQuery.Missing
                SnapshotAcquisition.Stale -> SnapshotQuery.Stale
                is SnapshotAcquisition.Failure -> SnapshotQuery.Failure(acquired.cause)
            }
        }
    }

    private fun crossFileGuard(
        request: RenameRequest,
        compiled: CompiledDocumentSnapshot,
        sources: RenameSourceSnapshots,
    ): CrossFileUseGuard {
        val workspace = request.source.workspace
        return CrossFileUseGuard(
            checkNotNull(compilationService.getWorkspaceSymbolIndex()),
            { request.buffers?.documents?.mapValues { it.value.content } ?: emptyMap() },
            WorkspaceLayout(
                sources = { workspace.sources },
                roots = { sources.roots },
                sourceRoots = { workspace.sourceRoots },
            ),
            caches,
            scanLimits,
            RenameScanWitness(request, compiled, sources, compilationService, caches.diskText),
        )
    }
}
