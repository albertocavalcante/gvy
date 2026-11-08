package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** Acquires current source before querying an engine and rejects results for changed buffers. */
internal class HoverRequestHandler(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
) {
    suspend fun getHover(params: HoverParams): Hover {
        currentCoroutineContext().ensureActive()
        val uri = URI.create(params.textDocument.uri)
        val input = documents.getSnapshot(uri)
        compilation.ensureCompiled(uri)
        currentCoroutineContext().ensureActive()
        requireUnchanged(uri, input)

        val compiled = compilation.getCompiledSnapshot(uri) ?: input?.let {
            // Worker selection can invalidate an awaited compilation during initialization.
            // Compile the captured open buffer directly once rather than returning a missing-cache placeholder.
            compilation.compile(uri, it.content)
            currentCoroutineContext().ensureActive()
            requireUnchanged(uri, input)
            compilation.getCompiledSnapshot(uri)
        } ?: return emptyHover()
        if (input != null && compiled.sourceText != input.content) throw contentModified(uri)

        val session = compilation.getHoverSession(uri, compiled)
        val result = session.features.hoverProvider.getHover(params)
        currentCoroutineContext().ensureActive()
        requireUnchanged(uri, input)
        return result
    }

    private fun requireUnchanged(uri: URI, input: DocumentSnapshot?) {
        if (documents.getSnapshot(uri) != input) throw contentModified(uri)
    }

    private fun contentModified(uri: URI) = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Document changed during hover: $uri", null),
    )

    private fun emptyHover() = Hover(MarkupContent(MarkupKind.MARKDOWN, "_No information available (Not Compiled)_"))
}
