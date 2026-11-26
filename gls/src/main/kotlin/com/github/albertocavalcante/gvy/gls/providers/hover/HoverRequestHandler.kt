// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.PreparedFeature
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.compilation.deliveryInputsCurrent
import com.github.albertocavalcante.gvy.gls.engine.api.LanguageEngine
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.utils.failingAsInternalError
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import com.github.albertocavalcante.gvy.gls.engine.api.HoverProvider as EngineHoverProvider

internal interface HeldHoverProvider : EngineHoverProvider {
    suspend fun prepareHover(params: HoverParams): PreparedFeature<Hover>

    override suspend fun getHover(params: HoverParams): Hover {
        val feature = prepareHover(params)
        val result = runSuspendCatching { feature.compute() }
        if (!deliveryInputsCurrent(feature.publicationsCurrent, feature.qualify)) {
            throw hoverChanged(URI.create(params.textDocument.uri))
        }
        return result.getOrThrow()
    }
}

private fun hoverChanged(uri: URI) = ResponseErrorException(
    ResponseError(ResponseErrorCode.ContentModified, "Document changed during hover: $uri", null),
)

internal class HoverRequestHandler(
    private val compilation: GroovyCompilationService,
    private val documents: DocumentProvider,
) {
    internal data class Request(
        val source: SnapshotSource,
        val params: HoverParams,
        val engine: LanguageEngine,
        val jenkins: JenkinsQuerySnapshot,
    )

    fun capture(params: HoverParams): Request {
        val uri = URI.create(params.textDocument.uri)
        val source = compilation.captureSnapshotSource(uri)
        if (documents !== compilation.documents || source.buffer?.snapshot !== documents.getSnapshot(uri)) {
            throw hoverChanged(uri)
        }
        val copied = HoverParams(
            TextDocumentIdentifier(uri.toString()),
            Position(params.position.line, params.position.character),
        )
        return Request(source, copied, compilation.captureHoverEngine(), JenkinsQuerySnapshot.capture(compilation, uri))
    }

    suspend fun getHover(params: HoverParams): Hover = getHover(capture(params))

    suspend fun getHover(request: Request): Hover = failingAsInternalError("computing hover") {
        val uri = request.source.uri
        val acquired = compilation.acquireHoverSnapshot(request.source) {
            request.jenkins.isCurrent() && compilation.isHoverEngineCurrent(request.engine)
        }
        val outcome = acquired.queryFeature(
            publicationsCurrent = {
                request.jenkins.authoritiesCurrent() && compilation.isHoverEngineCurrent(request.engine)
            },
            requestCurrent = request.jenkins::isCurrent,
        ) { held ->
            if (!compilation.isHoverEngineCurrent(request.engine) || !request.jenkins.isCurrent()) {
                throw hoverChanged(uri)
            }
            val session = compilation.getHoverSession(acquired.source, held, request.engine, request.jenkins)
            val provider = session.features.hoverProvider
            if (provider is HeldHoverProvider) {
                provider.prepareHover(request.params)
            } else {
                PreparedFeature(qualify = request.jenkins::isCurrent) { provider.getHover(request.params) }
            }
        }
        when (outcome) {
            is SnapshotQuery.Answer -> outcome.value
            SnapshotQuery.Missing -> emptyHover()
            SnapshotQuery.Stale -> throw hoverChanged(uri)
            is SnapshotQuery.Failure -> throw outcome.cause
        }
    }

    private fun emptyHover() = Hover(MarkupContent(MarkupKind.MARKDOWN, ""))
}
