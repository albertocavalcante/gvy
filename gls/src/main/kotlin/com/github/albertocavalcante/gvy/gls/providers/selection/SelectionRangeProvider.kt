// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.nativeapi.ParseMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** Acquires coherent source/AST inputs; selection construction itself is pure. */
internal class SelectionRangeProvider(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
) {
    internal data class Request(
        val uri: URI,
        val source: SnapshotSource,
        val original: DocumentSnapshot?,
        val positions: List<Position>,
    )

    fun capture(params: SelectionRangeParams): Request {
        val positions = copyRequestedPositions(params)
        val documentUri =
            params.textDocument?.uri ?: fail(ResponseErrorCode.InvalidParams, "Missing selection document")
        val uri = try {
            URI.create(documentUri)
        } catch (_: IllegalArgumentException) {
            fail(ResponseErrorCode.InvalidParams, "Invalid selection document URI")
        }
        val source = compilation.captureSnapshotSource(uri)
        return Request(uri, source, documents.getSnapshot(uri), positions)
    }

    suspend fun provide(request: Request): List<SelectionRange>? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val uri = request.uri
        val source = request.source
        val original = request.original
        val copied = request.positions
        if (documents !== compilation.documents || !source.isCurrent() || source.buffer?.snapshot !== original) {
            fail(ResponseErrorCode.ContentModified, "Document changed during selection")
        }
        if (copied.isEmpty()) return emptyList()
        if (original == null) return null
        val index = TextIndex(original.content)
        val positions = copied.map { position ->
            val line = index.lineRange(position.line)
                ?: fail(ResponseErrorCode.InvalidParams, "Selection line is outside the document")
            Position(position.line, minOf(position.character, line.end.character))
        }
        return when (
            val result = compilation.queryCapturedSnapshot(source, Phases.CONVERSION, ParseMode.WORKSPACE) {
                val model = it.parseResult.astModel
                val structures = model.getNodes(uri).mapNotNull { node ->
                    context.ensureActive()
                    if (model.getUri(node) != uri) return@mapNotNull null
                    index.utf16Range(
                        Range(
                            Position(node.lineNumber - 1, node.columnNumber - 1),
                            Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
                        ),
                    )
                }
                SelectionRangeBuilder(index, structures).build(positions) { context.ensureActive() }
            }
        ) {
            is SnapshotQuery.Answer -> result.value
            SnapshotQuery.Missing -> null
            SnapshotQuery.Stale -> fail(ResponseErrorCode.ContentModified, "Document changed during selection")
            is SnapshotQuery.Failure -> throw result.cause
        }
    }

    private fun copyRequestedPositions(params: SelectionRangeParams): List<Position> {
        val requested = params.positions ?: fail(ResponseErrorCode.InvalidParams, "Missing selection positions")
        if (requested.size > MAX_SELECTION_POSITIONS) {
            fail(ResponseErrorCode.InvalidParams, "At most $MAX_SELECTION_POSITIONS selection positions are supported")
        }
        return requested.map { position ->
            if (position == null) fail(ResponseErrorCode.InvalidParams, "Null selection position")
            if (position.line < 0 || position.character < 0) {
                fail(ResponseErrorCode.InvalidParams, "Negative selection position")
            }
            Position(position.line, position.character)
        }
    }

    private fun fail(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))

    companion object {
        // Reject oversized batches rather than dropping entries and breaking response cardinality.
        internal const val MAX_SELECTION_POSITIONS = 1024
    }
}
