// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.folding

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.nativeapi.ParseMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** Acquires matching source and parse data without publishing state from a folding query. */
internal class FoldingRangeRequestHandler(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
) {
    internal data class Request(val uri: URI, val source: SnapshotSource, val original: DocumentSnapshot?)

    fun capture(params: FoldingRangeRequestParams): Request {
        val text = params.textDocument?.uri ?: fail(ResponseErrorCode.InvalidParams, "Missing folding document")
        val uri = try {
            URI.create(text)
        } catch (_: IllegalArgumentException) {
            fail(ResponseErrorCode.InvalidParams, "Invalid folding document URI")
        }
        val source = compilation.captureSnapshotSource(uri)
        return Request(uri, source, documents.getSnapshot(uri))
    }

    suspend fun provide(request: Request): List<FoldingRange> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val (uri, source, original) = request
        if (documents !== compilation.documents || !source.isCurrent() || source.buffer?.snapshot !== original) {
            fail(ResponseErrorCode.ContentModified, "Document changed during folding")
        }
        if (original == null) return emptyList()
        return when (
            val result = compilation.queryCapturedSnapshot(source, Phases.CONVERSION, ParseMode.WORKSPACE) {
                buildFoldingRanges(uri, it.sourceText, it.parseResult.astModel) { context.ensureActive() }
            }
        ) {
            is SnapshotQuery.Answer -> result.value
            SnapshotQuery.Missing -> emptyList()
            SnapshotQuery.Stale -> fail(ResponseErrorCode.ContentModified, "Document changed during folding")
            is SnapshotQuery.Failure -> throw result.cause
        }
    }

    private fun fail(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))
}
