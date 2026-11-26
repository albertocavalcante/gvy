// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotSource
import com.github.albertocavalcante.gvy.gls.compilation.deliveryInputsCurrent
import com.github.albertocavalcante.gvy.gls.project.JenkinsQueryFacts
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.utils.failingAsInternalError
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDetector
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** Acquires one captured source/parse pair before generating Groovy and Jenkins tokens. */
class SemanticTokensHandler(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {
    internal data class Request(val source: SnapshotSource, val jenkins: JenkinsQuerySnapshot)

    internal fun capture(uri: URI): Request {
        val source = compilationService.captureSnapshotSource(uri)
        if (documentProvider !== compilationService.documents ||
            source.buffer?.snapshot !== documentProvider.getSnapshot(uri)
        ) {
            throw contentModified()
        }
        return Request(source, JenkinsQuerySnapshot.capture(compilationService, uri))
    }

    suspend fun getSemanticTokens(uri: URI): SemanticTokens = getSemanticTokens(capture(uri))

    internal suspend fun getSemanticTokens(request: Request): SemanticTokens =
        failingAsInternalError("generating semantic tokens") {
            val uri = request.source.uri
            val context = currentCoroutineContext()
            context.ensureActive()
            if (request.source.buffer == null) {
                val current = deliveryInputsCurrent({
                    request.source.isCurrent() && request.jenkins.authoritiesCurrent()
                }, request.jenkins::isCurrent)
                if (!current) throw contentModified()
                return@failingAsInternalError SemanticTokens(emptyList())
            }
            val jenkins = request.jenkins
            if (!jenkins.isCurrent()) throw contentModified()
            val outcome = compilationService.queryCapturedSnapshot(
                request.source,
                Phases.CANONICALIZATION,
                ParseMode.WORKSPACE,
            ) { held ->
                val facts = jenkins.materialize()
                context.ensureActive()
                val result = generate(uri, held.sourceText, held.parseResult, facts) { context.ensureActive() }
                result to facts
            }
            val facts = if (outcome is SnapshotQuery.Answer) outcome.value.second else null
            val current = deliveryInputsCurrent({
                request.source.isCurrent() && (facts?.publicationsCurrent?.invoke() ?: jenkins.authoritiesCurrent())
            }) {
                facts?.isCurrent() ?: jenkins.isCurrent()
            }
            if (!current) throw contentModified()
            when (outcome) {
                is SnapshotQuery.Answer -> outcome.value.first
                SnapshotQuery.Missing -> SemanticTokens(emptyList())
                SnapshotQuery.Stale -> throw contentModified()
                is SnapshotQuery.Failure -> throw outcome.cause
            }
        }

    private fun contentModified() = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Document changed during semantic tokens", null),
    )

    private fun generate(
        uri: URI,
        source: String,
        parsed: ParseResult,
        jenkins: JenkinsQueryFacts,
        checkCancelled: () -> Unit,
    ): SemanticTokens {
        val module = parsed.ast
        val unused = module?.let { UnusedImportDetector.detectUnusedImports(it).toSet() }.orEmpty()
        checkCancelled()
        val input = SemanticTokenInput(parsed.astModel, uri, unused, module, source)
        val groovyTokens = GroovySemanticTokenProvider.tokensForSnapshot(input, checkCancelled)
        val jenkinsTokens = JenkinsSemanticTokenProvider.tokensForSnapshot(
            input,
            jenkins.isJenkinsFile,
            jenkins.variables,
            checkCancelled,
        )
        val allTokens = SemanticTokensEncoder.combine(groovyTokens, jenkinsTokens).sortedWith(
            compareBy<JenkinsSemanticTokenProvider.SemanticToken> { it.line }
                .thenBy { it.startChar }.thenBy { it.length }.thenBy { it.tokenType }.thenBy { it.tokenModifiers },
        )
        checkCancelled()
        return SemanticTokens(SemanticTokensEncoder.encode(allTokens))
    }
}
