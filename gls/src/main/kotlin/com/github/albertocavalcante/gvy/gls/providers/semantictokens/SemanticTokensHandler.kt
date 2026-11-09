package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDetector
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.util.concurrent.CancellationException

/** Acquires one captured source/parse pair before generating Groovy and Jenkins tokens. */
class SemanticTokensHandler(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun getSemanticTokens(uri: URI): SemanticTokens {
        val context = currentCoroutineContext()
        context.ensureActive()
        val original = documentProvider.getSnapshot(uri) ?: return SemanticTokens(emptyList())
        val result = try {
            val cached = compilationService.getCompiledSnapshot(uri)?.takeIf { it.sourceText == original.content }
            requireCurrent(uri, original)
            val parsed = cached?.parseResult
                ?: compilationService.compileTransient(uri, original.content, Phases.CANONICALIZATION)
            context.ensureActive()
            requireCurrent(uri, original)
            generate(uri, original.content, parsed) { context.ensureActive() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseErrorException) {
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logger.error(error) { "Failed to generate semantic tokens for $uri" }
            SemanticTokens(emptyList())
        }
        context.ensureActive()
        requireCurrent(uri, original)
        return result
    }

    private fun generate(uri: URI, source: String, parsed: ParseResult, checkCancelled: () -> Unit): SemanticTokens {
        val jenkins = compilationService.workspaceManager.getJenkinsCapabilities()
        val isJenkinsFile = jenkins?.isJenkinsFile(uri) ?: false
        val varsNames = jenkins?.getGlobalVariables()?.map { it.name }?.toSet().orEmpty()
        val module = parsed.ast
        val unused = module?.let { UnusedImportDetector.detectUnusedImports(it).toSet() }.orEmpty()
        checkCancelled()
        val input = SemanticTokenInput(parsed.astModel, uri, unused, module, source)
        val groovyTokens = GroovySemanticTokenProvider.tokensForSnapshot(input, checkCancelled)
        val jenkinsTokens = JenkinsSemanticTokenProvider.tokensForSnapshot(
            input,
            isJenkinsFile,
            varsNames,
            checkCancelled,
        )
        val allTokens = SemanticTokensEncoder.combine(groovyTokens, jenkinsTokens).sortedWith(
            compareBy<JenkinsSemanticTokenProvider.SemanticToken> { it.line }
                .thenBy { it.startChar }.thenBy { it.length }.thenBy { it.tokenType }.thenBy { it.tokenModifiers },
        )
        checkCancelled()
        return SemanticTokens(SemanticTokensEncoder.encode(allTokens))
    }

    private fun requireCurrent(uri: URI, original: DocumentSnapshot) {
        if (documentProvider.getSnapshot(uri) != original) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.ContentModified, "Document changed during semantic tokens", null),
            )
        }
    }
}
