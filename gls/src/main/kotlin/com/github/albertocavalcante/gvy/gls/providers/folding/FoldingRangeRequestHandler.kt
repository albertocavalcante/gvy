package com.github.albertocavalcante.gvy.gls.providers.folding

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
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
    suspend fun provide(params: FoldingRangeRequestParams): List<FoldingRange> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val text = params.textDocument?.uri ?: fail(ResponseErrorCode.InvalidParams, "Missing folding document")
        val uri = try {
            URI.create(text)
        } catch (_: IllegalArgumentException) {
            fail(ResponseErrorCode.InvalidParams, "Invalid folding document URI")
        }
        val original = documents.getSnapshot(uri) ?: return emptyList()
        val cached = compilation.getCompiledSnapshot(uri)?.takeIf { it.sourceText == original.content }
        requireCurrent(uri, original)
        val parsed = cached?.parseResult ?: compilation.compileTransient(uri, original.content, Phases.CONVERSION)
        context.ensureActive()
        requireCurrent(uri, original)
        val result = buildFoldingRanges(uri, original.content, parsed.astModel) { context.ensureActive() }
        context.ensureActive()
        requireCurrent(uri, original)
        return result
    }

    private fun requireCurrent(uri: URI, original: DocumentSnapshot) {
        if (documents.getSnapshot(uri) != original) {
            fail(ResponseErrorCode.ContentModified, "Document changed during folding")
        }
    }

    private fun fail(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))
}
