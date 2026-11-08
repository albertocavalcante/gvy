package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    suspend fun provide(params: SelectionRangeParams): List<SelectionRange>? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val requested = params.positions ?: fail(ResponseErrorCode.InvalidParams, "Missing selection positions")
        if (requested.size > MAX_SELECTION_POSITIONS) {
            fail(ResponseErrorCode.InvalidParams, "At most $MAX_SELECTION_POSITIONS selection positions are supported")
        }
        val copied = requested.map { position ->
            if (position == null) fail(ResponseErrorCode.InvalidParams, "Null selection position")
            if (position.line < 0 || position.character < 0) {
                fail(ResponseErrorCode.InvalidParams, "Negative selection position")
            }
            Position(position.line, position.character)
        }
        val documentUri =
            params.textDocument?.uri ?: fail(ResponseErrorCode.InvalidParams, "Missing selection document")
        val uri = try {
            URI.create(documentUri)
        } catch (_: IllegalArgumentException) {
            fail(ResponseErrorCode.InvalidParams, "Invalid selection document URI")
        }
        if (copied.isEmpty()) return emptyList()
        val original = documents.getSnapshot(uri) ?: return null
        val index = TextIndex(original.content)
        val positions = copied.map { position ->
            val line = index.lineRange(position.line)
                ?: fail(ResponseErrorCode.InvalidParams, "Selection line is outside the document")
            Position(position.line, minOf(position.character, line.end.character))
        }
        compilation.ensureCompiled(uri) ?: compilation.compile(uri, original.content)
        context.ensureActive()
        requireCurrent(uri, original)
        val compiled = compilation.getCompiledSnapshot(uri)
        // A stale or missing AST contributes no ranges; current text still supports safe minimal selections.
        val model = compiled?.takeIf { it.sourceText == original.content }?.parseResult?.astModel
        val structures = model?.getNodes(uri).orEmpty().mapNotNull { node ->
            context.ensureActive()
            if (model?.getUri(node) != uri) return@mapNotNull null
            index.utf16Range(
                Range(
                    Position(node.lineNumber - 1, node.columnNumber - 1),
                    Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
                ),
            )
        }
        val result = SelectionRangeBuilder(index, structures).build(positions) { context.ensureActive() }
        context.ensureActive()
        requireCurrent(uri, original)
        return result
    }

    private fun requireCurrent(uri: URI, original: DocumentSnapshot) {
        if (documents.getSnapshot(uri) != original) {
            fail(ResponseErrorCode.ContentModified, "Document changed during selection")
        }
    }

    private fun fail(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))

    companion object {
        // Reject oversized batches rather than dropping entries and breaking response cardinality.
        internal const val MAX_SELECTION_POSITIONS = 1024
    }
}
