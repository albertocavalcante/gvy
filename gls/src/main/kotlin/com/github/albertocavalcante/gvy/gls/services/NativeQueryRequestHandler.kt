// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.HoverMemberLookup
import com.github.albertocavalcante.gvy.gls.compilation.NativeQueryContext
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.compilation.deliveryInputsCurrent
import com.github.albertocavalcante.gvy.gls.compilation.inputsCurrent
import com.github.albertocavalcante.gvy.gls.engine.api.LanguageEngine
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

internal class NativeQueryRequestHandler(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
) {
    class Request<T>(val source: SnapshotSource, val engine: LanguageEngine, val input: T)

    fun <T> capture(uri: URI, input: T): Request<T> {
        val source = compilation.captureSnapshotSource(uri)
        if (documents !== compilation.documents || source.buffer?.snapshot !== documents.getSnapshot(uri)) {
            throw changed(uri)
        }
        return Request(source, compilation.captureHoverEngine(), input)
    }

    suspend fun <P, T> query(request: Request<P>, compute: suspend (NativeQueryContext, URI, P) -> List<T>): List<T> {
        val source = request.source
        var members: HoverMemberLookup? = null
        fun current() =
            source.isCurrent() && source.workerCurrent() && compilation.isHoverEngineCurrent(request.engine) &&
                members?.isDatabaseCurrent() != false
        if (!currentCoroutineContext().inputsCurrent(::current)) throw changed(source.uri)
        val result = runSuspendCatching {
            compilation.queryCapturedSnapshot(source, Phases.CANONICALIZATION, null) { held ->
                val lookup = HoverMemberLookup.forRequest(held) { compilation.referenceReadiness(source) }
                members = lookup
                val query = NativeQueryContext(held, lookup)
                withContext(query.binaryScope) { compute(query, source.uri, request.input) }
            }
        }
        // Both the producer's context check above and the peer qualification here may execute project code.
        val deliver = deliveryInputsCurrent(::current) {
            result.getOrNull() !== SnapshotQuery.Stale && members?.isCurrent() != false
        }
        if (!deliver) throw changed(source.uri)
        return result.getOrThrow().values(source.uri)
    }

    private fun <T> SnapshotQuery<List<T>>.values(uri: URI): List<T> = when (this) {
        is SnapshotQuery.Answer -> value
        SnapshotQuery.Missing -> emptyList()
        SnapshotQuery.Stale -> throw changed(uri)
        is SnapshotQuery.Failure -> throw cause
    }

    private fun changed(uri: URI) = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Inputs changed during native query for $uri", null),
    )
}
