package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.config.DiagnosticConfig
import com.github.albertocavalcante.groovylsp.providers.diagnostics.StreamingDiagnosticProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Service for collecting diagnostics from multiple streaming providers.
 *
 * Providers are executed concurrently using Flow-based composition, with configuration-based
 * filtering (disabled/enabled providers) and graceful error handling.
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
        private val logger = LoggerFactory.getLogger(DiagnosticsService::class.java)
    }

    /**
     * Get diagnostics from all enabled providers using Flow-based concurrent execution.
     *
     * NOTE: Uses flatMapMerge for concurrent provider execution. This is a performance
     * optimization that allows multiple slow providers (like CodeNarc) to run in parallel.
     *
     * TODO(#454): Add performance metrics to track per-provider execution time.
     *   See: https://github.com/albertocavalcante/groovy-lsp/issues/454
     * TODO(#455): Consider adding timeout per provider to prevent hanging.
     *   See: https://github.com/albertocavalcante/groovy-lsp/issues/455
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun getDiagnostics(uri: URI, content: String): List<Diagnostic> = withContext(Dispatchers.IO) {
        val enabledProviders = providers.filter { config.isProviderEnabled(it) }

        if (enabledProviders.isEmpty()) {
            logger.debug("No enabled diagnostic providers for {}", uri)
            return@withContext emptyList()
        }

        logger.debug(
            "Running {} enabled providers for {}: {}",
            enabledProviders.size,
            uri,
            enabledProviders.map { it.id },
        )

        try {
            // Execute all providers concurrently and collect results
            // NOTE: flatMapMerge concurrency parameter controls parallelism
            // Setting to DEFAULT (16) allows many providers to run concurrently
            enabledProviders.asFlow()
                .flatMapMerge { provider ->
                    // Wrap each provider in error handling
                    flow {
                        try {
                            logger.debug("Executing provider: {}", provider.id)
                            val startTime = System.nanoTime()

                            emitAll(provider.provideDiagnostics(uri, content))

                            val elapsedMs = (System.nanoTime() - startTime) / NANOS_TO_MILLIS
                            logger.debug("Provider {} completed in {}ms", provider.id, elapsedMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // NOTE: Error isolation - one provider failure doesn't stop others
                            // TODO(#456): Consider publishing provider-specific error diagnostics.
                            //   See: https://github.com/albertocavalcante/groovy-lsp/issues/456
                            logger.error("Provider {} failed for {}: {}", provider.id, uri, e.message, e)
                            // Don't re-throw - continue with other providers
                        }
                    }
                }
                .toList()
        } catch (e: CancellationException) {
            // Job cancellation is expected during rapid document edits (debouncing)
            logger.debug("Diagnostic collection cancelled for {}", uri)
            throw e // Re-throw to preserve cancellation
        } catch (e: Exception) {
            // NOTE: This should rarely happen due to per-provider error handling above
            logger.error("Unexpected error during diagnostic collection for {}", uri, e)
            emptyList()
        }
    }
}
