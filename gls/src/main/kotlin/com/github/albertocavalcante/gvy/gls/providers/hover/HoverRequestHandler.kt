// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
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
        val compiled = acquireSnapshot(uri, input) ?: return emptyHover()
        if (input != null && compiled.sourceText != input.content) throw contentModified(uri)

        val session = compilation.getHoverSession(uri, compiled)
        val result = session.features.hoverProvider.getHover(params)
        currentCoroutineContext().ensureActive()
        requireUnchanged(uri, input)
        return result
    }

    private suspend fun acquireSnapshot(uri: URI, input: DocumentSnapshot?): CompiledDocumentSnapshot? {
        val acquired = compilation.ensureCompiled(uri)
        currentCoroutineContext().ensureActive()
        requireUnchanged(uri, input)
        if (hasNoCurrentAst(uri, input, acquired)) return null

        return compilation.getCompiledSnapshot(uri) ?: input?.let { snapshot ->
            // Worker selection can invalidate an awaited compilation during initialization.
            // Compile the captured open buffer directly once rather than returning a missing-cache placeholder.
            val retried = compilation.compile(uri, snapshot.content)
            currentCoroutineContext().ensureActive()
            requireUnchanged(uri, input)
            if (hasNoCurrentAst(uri, input, retried)) null else compilation.getCompiledSnapshot(uri)
        }
    }

    private fun hasNoCurrentAst(uri: URI, input: DocumentSnapshot?, result: CompilationResult?): Boolean {
        if (result == null || !result.parseCompleted || result.ast != null) return false
        if (input != null && result.sourceText != input.content) throw contentModified(uri)
        // A completed parse can leave the last successful AST cached. It cannot describe this incomplete text.
        return true
    }

    private fun requireUnchanged(uri: URI, input: DocumentSnapshot?) {
        // A reopened buffer can have equal text and version but belongs to a different lifecycle.
        if (documents.getSnapshot(uri) !== input) throw contentModified(uri)
    }

    private fun contentModified(uri: URI) = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Document changed during hover: $uri", null),
    )

    private fun emptyHover() = Hover(MarkupContent(MarkupKind.MARKDOWN, ""))
}
