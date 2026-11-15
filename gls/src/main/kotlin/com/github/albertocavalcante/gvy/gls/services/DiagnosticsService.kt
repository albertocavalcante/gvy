// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.config.DiagnosticConfig
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import java.net.URI

/**
 * Service for collecting diagnostics from multiple streaming providers.
 *
 * Providers execute concurrently with bounded parallelism and configuration-based filtering.
 * Ordinary exceptions are recorded alongside partial diagnostics; cancellation and fatal errors propagate.
 *
 * @param providers List of diagnostic providers to use
 * @param config Configuration for filtering providers
 */
class DiagnosticsService(
    private val providers: List<StreamingDiagnosticProvider>,
    private val config: DiagnosticConfig = DiagnosticConfig(),
) {

    companion object {
        private const val NANOS_TO_MILLIS = 1_000_000L
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Best-effort list view for existing callers. Use [collectDiagnostics] for provider failure details.
     */
    suspend fun getDiagnostics(uri: URI, content: String): List<Diagnostic> =
        collectDiagnostics(uri, content).diagnostics

    /**
     * Collects all enabled providers, preserving diagnostic arrival order and registration-ordered outcomes.
     * Awaiting each provider explicitly also observes a provider that cancels its own child coroutine.
     *
     * TODO(#454): Add performance metrics to track per-provider execution time.
     *   See: https://github.com/albertocavalcante/groovy-lsp/issues/454
     * TODO(#455): Consider adding timeout per provider to prevent hanging.
     *   See: https://github.com/albertocavalcante/groovy-lsp/issues/455
     */
    suspend fun collectDiagnostics(uri: URI, content: String): DiagnosticsCollectionResult =
        collectDiagnostics(uri, content, compilation = null)

    @OptIn(FlowPreview::class)
    internal suspend fun collectDiagnostics(
        uri: URI,
        content: String,
        compilation: CompilationResult?,
    ): DiagnosticsCollectionResult = withContext(Dispatchers.IO) {
        compilation?.let {
            check(it.parseCompleted) { "Incomplete compilation cannot establish diagnostic context for $uri" }
            require(it.sourceText == content) {
                "Compilation source does not match the diagnostic snapshot for $uri"
            }
        }
        val enabledProviders = providers.filter { config.isProviderEnabled(it) }
            .map { EnabledProvider(it.id, it) }

        if (enabledProviders.isEmpty()) {
            logger.debug { "No enabled diagnostic providers for $uri" }
            return@withContext DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        }

        logger.debug {
            "Running ${enabledProviders.size} enabled providers for $uri: ${enabledProviders.map { it.id }}"
        }

        try {
            coroutineScope {
                val emissions = Channel<Diagnostic>(Channel.BUFFERED)
                val diagnostics = async { emissions.consumeAsFlow().toList() }
                // Preserve flatMapMerge's existing concurrency budget, including its configured override.
                val permits = Semaphore(DEFAULT_CONCURRENCY)
                val pending = enabledProviders.map { provider ->
                    async {
                        permits.withPermit {
                            collectProvider(provider, uri, content, emissions, compilation)
                        }
                    }
                }
                val failures = try {
                    pending.awaitAll().filterNotNull()
                } finally {
                    pending.forEach { it.cancel() }
                    emissions.close()
                }

                DiagnosticsCollectionResult(
                    diagnostics = diagnostics.await(),
                    providerIds = enabledProviders.map { it.id },
                    failures = failures,
                )
            }
        } catch (e: CancellationException) {
            // Job cancellation is expected during rapid document edits (debouncing)
            logger.debug { "Diagnostic collection cancelled for $uri" }
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // Provider exceptions become outcomes; cancellation and Errors propagate.
    private suspend fun collectProvider(
        provider: EnabledProvider,
        uri: URI,
        content: String,
        emissions: SendChannel<Diagnostic>,
        compilation: CompilationResult?,
    ): DiagnosticProviderFailure? {
        logger.debug { "Executing provider: ${provider.id}" }
        val startTime = System.nanoTime()
        return try {
            val stream = if (compilation == null) {
                provider.provider.provideDiagnostics(uri, content)
            } else {
                provider.provider.provideDiagnostics(uri, content, compilation)
            }
            stream.collect { emissions.send(it) }
            currentCoroutineContext().ensureActive()
            val elapsedMs = (System.nanoTime() - startTime) / NANOS_TO_MILLIS
            logger.debug { "Provider ${provider.id} completed in ${elapsedMs}ms" }
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            logger.error(failure) { "Provider ${provider.id} failed for $uri: ${failure.message}" }
            DiagnosticProviderFailure(provider.id, failure.javaClass.name, failure.message)
        }
    }

    private data class EnabledProvider(val id: String, val provider: StreamingDiagnosticProvider)
}
