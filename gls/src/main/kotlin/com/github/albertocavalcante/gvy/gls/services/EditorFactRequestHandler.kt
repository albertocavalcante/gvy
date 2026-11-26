// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.CompilationEnsurer
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.QualifiedReferences
import com.github.albertocavalcante.gvy.gls.compilation.ReferenceReadiness
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.compilation.inputsCurrent
import com.github.albertocavalcante.gvy.gls.compilation.queryCurrentSnapshot
import com.github.albertocavalcante.gvy.gls.providers.callhierarchy.CallHierarchyProvider
import com.github.albertocavalcante.gvy.gls.providers.highlight.DocumentHighlightProvider
import com.google.gson.JsonPrimitive
import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.future
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.util.concurrent.CompletableFuture

/** Request boundaries for local editor facts and the call hierarchy. */
internal class EditorFactRequestHandler(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val callHierarchyProvider: CallHierarchyProvider,
    private val documentHighlightProvider: DocumentHighlightProvider,
    private val logger: KLogger,
) {
    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> {
        val request = runCatching {
            val uri = URI.create(params.textDocument.uri)
            val position = Position(params.position.line, params.position.character)
            if (documentProvider !== compilationService.documents) throw contentModified()
            Triple(uri, position, compilationService.captureSnapshotSource(uri))
        }
        return coroutineScope.future {
            val (uri, position, source) = request.getOrThrow()
            queryEditorFact(source) { snapshot ->
                callHierarchyProvider.prepareCallHierarchy(uri, position, snapshot).onEach { item ->
                    item.data = checkNotNull(snapshot.sourceIdentity)
                }
            }
        }
    }

    private suspend fun <T> queryEditorFact(
        source: SnapshotSource,
        compute: (CompiledDocumentSnapshot) -> List<T>,
    ): List<T> = when (
        val result = compilationService.queryCapturedSnapshot(source, Phases.CANONICALIZATION, null) { compute(it) }
    ) {
        is SnapshotQuery.Answer -> result.value
        SnapshotQuery.Missing -> emptyList()
        SnapshotQuery.Stale -> throw ResponseErrorException(
            ResponseError(ResponseErrorCode.ContentModified, "Document changed during request", null),
        )
        is SnapshotQuery.Failure -> throw result.cause
    }

    fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams,
    ): CompletableFuture<List<CallHierarchyIncomingCall>> {
        val request = runCatching {
            val captured = captureFollowup(params.item)
            captured to compilationService.referenceReadiness(captured.source)
        }
        return coroutineScope.future {
            val (captured, readiness) = request.getOrThrow()
            val uri = captured.source.uri
            val result = queryCurrentSnapshot(captured.source::isCurrent) {
                incomingCalls(uri, captured.position, captured.identity, readiness)
            }
            when (result) {
                is SnapshotQuery.Answer -> result.value
                else -> throw queryError(uri, result)
            }
        }
    }

    private data class Followup(val source: SnapshotSource, val position: Position, val identity: String?)

    private fun captureFollowup(item: CallHierarchyItem): Followup {
        if (documentProvider !== compilationService.documents) throw contentModified()
        val uri = URI.create(item.uri)
        val start = item.selectionRange.start
        return Followup(
            compilationService.captureSnapshotSource(uri),
            Position(start.line, start.character),
            preparedIdentity(item.data),
        )
    }

    private suspend fun incomingCalls(
        uri: URI,
        position: Position,
        identity: String?,
        readiness: ReferenceReadiness,
    ): List<CallHierarchyIncomingCall> {
        requirePreparedFence(identity, readiness.sourceIdentity)
        val initialOrigin = captureInitialOrigin(uri, identity)
        CompilationEnsurer(documentProvider, compilationService).ensureAllCompiled()
        readiness.prepareOpenSources(compilationService)
        compilationService.ensureCompiled(uri)
        return when (
            val result = readiness.query { references ->
                val held = HeldCallSources(references)
                val origin = held.capture(uri)
                requirePreparedIdentity(identity, origin.sourceIdentity)
                val answer = callHierarchyProvider.incomingCalls(uri, position, origin, references.index, held::capture)
                held.validate(initialOrigin)
                held.attachIdentities(answer)
                answer
            }
        ) {
            is SnapshotQuery.Answer -> result.value
            else -> throw queryError(uri, result)
        }
    }

    private suspend fun captureInitialOrigin(uri: URI, identity: String?): SnapshotAcquisition.Available {
        val acquisition = compilationService.acquireEditorSnapshot(uri)
        val initialOrigin = if (acquisition is SnapshotAcquisition.Available) {
            acquisition
        } else {
            throw initialAcquisitionError(acquisition)
        }
        if (!currentCoroutineContext().inputsCurrent(initialOrigin.isCurrent)) throw contentModified()
        requirePreparedIdentity(identity, initialOrigin.snapshot.sourceIdentity)
        return initialOrigin
    }

    private inner class HeldCallSources(private val references: QualifiedReferences) {
        private val held = mutableMapOf<URI, SnapshotAcquisition.Ready>()

        suspend fun capture(uri: URI): CompiledDocumentSnapshot {
            held[uri]?.let { return it.snapshot }
            val acquisition = compilationService.acquireSnapshot(uri, mode = references.modeFor(uri))
            val ready = when (acquisition) {
                is SnapshotAcquisition.Ready -> acquisition
                else -> throw acquisitionError(uri, acquisition)
            }
            if (!references.matches(uri, ready.snapshot)) throw contentModified()
            held[uri] = ready
            return ready.snapshot
        }

        suspend fun validate(initialOrigin: SnapshotAcquisition.Available) {
            val context = currentCoroutineContext()
            if (!context.inputsCurrent(initialOrigin.isCurrent) ||
                held.values.any { !context.inputsCurrent(it.isCurrent) }
            ) {
                throw contentModified()
            }
        }

        fun attachIdentities(calls: List<CallHierarchyIncomingCall>) {
            calls.forEach { call ->
                call.from.data = checkNotNull(held.getValue(URI.create(call.from.uri)).snapshot.sourceIdentity)
            }
        }
    }

    private fun acquisitionError(uri: URI, acquisition: SnapshotAcquisition): Exception = when (acquisition) {
        SnapshotAcquisition.Stale -> contentModified()
        SnapshotAcquisition.Missing, is SnapshotAcquisition.Partial -> referenceUnavailable(uri)
        is SnapshotAcquisition.Failure -> acquisition.cause
        is SnapshotAcquisition.Ready -> error("Ready acquisition has no error")
    }

    private fun initialAcquisitionError(acquisition: SnapshotAcquisition): Exception = when (acquisition) {
        SnapshotAcquisition.Stale, SnapshotAcquisition.Missing -> contentModified()
        is SnapshotAcquisition.Failure -> acquisition.cause
        is SnapshotAcquisition.Available -> error("Available acquisition has no error")
    }

    private fun queryError(uri: URI, result: SnapshotQuery<*>): Exception = when (result) {
        SnapshotQuery.Missing -> referenceUnavailable(uri)
        SnapshotQuery.Stale -> contentModified()
        is SnapshotQuery.Failure -> result.cause
        is SnapshotQuery.Answer -> error("Answered query has no error")
    }

    private fun contentModified() = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Inputs changed during call hierarchy", null),
    )

    private fun referenceUnavailable(uri: URI) = ResponseErrorException(
        ResponseError(ResponseErrorCode.RequestFailed, "Reference source unavailable for $uri", null),
    )

    fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams,
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> {
        val request = runCatching { captureFollowup(params.item) }
        return coroutineScope.future {
            val (source, position, identity) = request.getOrThrow()
            val uri = source.uri
            when (
                val result = compilationService.queryCapturedSnapshot(
                    source,
                    Phases.CANONICALIZATION,
                    null,
                ) { snapshot ->
                    requirePreparedIdentity(identity, snapshot.sourceIdentity)
                    callHierarchyProvider.outgoingCalls(uri, position, snapshot).onEach { call ->
                        call.to.data = checkNotNull(snapshot.sourceIdentity)
                    }
                }
            ) {
                is SnapshotQuery.Answer -> result.value
                SnapshotQuery.Missing, SnapshotQuery.Stale -> throw contentModified()
                is SnapshotQuery.Failure -> throw result.cause
            }
        }
    }

    private fun requirePreparedIdentity(identity: String?, sourceIdentity: String?) {
        if (identity == null) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.RequestFailed, "Missing call hierarchy identity", null),
            )
        }
        if (identity != sourceIdentity) throw contentModified()
    }

    private fun requirePreparedFence(identity: String?, fence: String) {
        if (identity == null || '/' !in identity) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.RequestFailed, "Missing call hierarchy identity", null),
            )
        }
        if (identity.substringBeforeLast('/') != fence) throw contentModified()
    }

    private fun preparedIdentity(data: Any?): String? = when (data) {
        is String -> data
        is JsonPrimitive -> if (data.isString) data.asString else null
        else -> null
    }

    fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> {
        val request = runCatching {
            val uri = URI.create(params.textDocument.uri)
            val position = Position(params.position.line, params.position.character)
            if (documentProvider !== compilationService.documents) throw contentModified()
            Triple(uri, position, compilationService.captureSnapshotSource(uri))
        }
        return coroutineScope.future {
            val (uri, position, source) = request.getOrThrow()
            logger.debug {
                "Document highlight requested for $uri at ${position.line}:${position.character}"
            }

            val highlights = queryEditorFact(source) { documentHighlightProvider.provideHighlights(uri, position, it) }
            logger.debug { "Found ${highlights.size} highlights" }
            highlights
        }
    }
}
