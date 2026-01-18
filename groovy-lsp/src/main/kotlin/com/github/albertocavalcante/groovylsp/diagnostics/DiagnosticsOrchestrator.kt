package com.github.albertocavalcante.groovylsp.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.services.DiagnosticsService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates diagnostic computation and publication for open documents.
 *
 * This class manages:
 * - Triggering diagnostic jobs asynchronously
 * - Cancelling stale diagnostic jobs (debouncing)
 * - Publishing diagnostics to the language client
 * - Coordinating parser diagnostics with additional diagnostic providers
 *
 * Extracted from GroovyTextDocumentService as part of god class refactoring (#951).
 */
class DiagnosticsOrchestrator(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    private val diagnosticsService: DiagnosticsService,
    private val documentProvider: DocumentProvider,
    private val serverConfiguration: ServerConfiguration,
    private val client: () -> LanguageClient?,
) {
    private val logger = KotlinLogging.logger {}

    // Track active diagnostic jobs per URI to cancel stale ones (debouncing/throttling)
    // Internal for access by CompilationEnsurer
    internal val diagnosticJobs = ConcurrentHashMap<URI, Job>()

    /**
     * Triggers diagnostics computation for the specified URI and content.
     *
     * This method:
     * 1. Cancels any existing diagnostic job for the URI
     * 2. Compiles the document asynchronously
     * 3. Publishes parser diagnostics immediately (for responsive UX)
     * 4. Runs additional diagnostic providers (CodeNarc, custom rules, etc.)
     * 5. Publishes merged diagnostics if additional diagnostics were found
     *
     * @param uri The document URI
     * @param content The document content
     */
    @Suppress("TooGenericExceptionCaught")
    fun trigger(uri: URI, content: String) {
        // Launch a new diagnostic job
        val job = coroutineScope.launch {
            val currentJob = coroutineContext[Job]
            try {
                runCatching {
                    // Use compileAsync for proper coordination
                    val result = compilationService.compileAsync(this, uri, content).await()

                    ensureActive() // Ensure job wasn't cancelled before publishing

                    val parserEnabled = serverConfiguration.diagnosticConfig.isProviderEnabled(
                        "parser",
                        enabledByDefault = true,
                    )
                    val parserDiagnostics = if (parserEnabled) result.diagnostics else emptyList()

                    // Publish compilation diagnostics first to keep UX responsive.
                    // NOTE: Tradeoff (See #564):
                    // This can result in two diagnostics publications (compile first, then provider merge),
                    // but avoids blocking syntax feedback on slow lint initialization.
                    publishDiagnostics(uri.toString(), parserDiagnostics)

                    val extraDiagnostics = diagnosticsService.getDiagnostics(uri, content)
                    val allDiagnostics = parserDiagnostics + extraDiagnostics

                    ensureActive()
                    if (extraDiagnostics.isNotEmpty()) {
                        publishDiagnostics(uri.toString(), allDiagnostics)
                    }

                    logger.debug { "Published ${allDiagnostics.size} diagnostics for $uri" }
                }.onFailure { e ->
                    when (e) {
                        is CompilationFailedException -> logger.error(e) { "Compilation failed for: $uri" }
                        is IllegalArgumentException -> logger.error(e) { "Invalid arguments for: $uri" }
                        is IOException -> logger.error(e) { "I/O error for: $uri" }
                        is CancellationException -> {
                            logger.debug { "Diagnostics job cancelled for: $uri" }
                            throw e
                        }
                        else -> logger.error(e) { "Unexpected error during diagnostics for: $uri" }
                    }
                }
            } finally {
                // Remove job from map if it's the current one
                diagnosticJobs.remove(uri, currentJob)
            }
        }

        // Atomically cancel existing job and register new one
        diagnosticJobs.compute(uri) { _, existingJob ->
            existingJob?.cancel()
            job
        }
    }

    /**
     * Cancels and removes any running diagnostic job for the specified URI.
     *
     * @param uri The document URI
     */
    fun cancelAndRemove(uri: URI) {
        diagnosticJobs.remove(uri)?.cancel()
    }

    /**
     * Refreshes diagnostics for all open documents.
     *
     * This triggers diagnostic computation for each document in the document provider's snapshot.
     * Useful when configuration changes or external factors require re-analysis.
     */
    fun refreshAll() {
        documentProvider.snapshot().forEach { (uri, content) ->
            trigger(uri, content)
            logger.info { "Triggered diagnostics refresh for $uri after dependency update" }
        }
    }

    /**
     * Waits for any ongoing diagnostics job for the given URI to complete.
     *
     * This is useful for testing to ensure compilation is done before making assertions.
     *
     * @param uri The document URI
     */
    suspend fun awaitDiagnostics(uri: URI) {
        diagnosticJobs[uri]?.join()
    }

    /**
     * Helper function to publish diagnostics with better readability.
     *
     * @param uri The document URI as string
     * @param diagnostics The list of diagnostics to publish
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<org.eclipse.lsp4j.Diagnostic>) {
        logger.debug { "Publishing ${diagnostics.size} diagnostics for $uri" }
        client()?.publishDiagnostics(
            PublishDiagnosticsParams().apply {
                this.uri = uri
                this.diagnostics = diagnostics
            },
        )
    }

    /**
     * Internal method for testing: Get the diagnostic job for a URI.
     * This should only be used in tests to verify job state.
     *
     * @param uri The document URI
     * @return The diagnostic job, or null if no job exists
     */
    internal fun getDiagnosticJob(uri: URI): Job? = diagnosticJobs[uri]

    /**
     * Get the concurrent hash map of diagnostic jobs.
     * This is used by CompilationEnsurer to coordinate compilation with diagnostic jobs.
     *
     * @return The diagnostic jobs map
     */
    internal fun getDiagnosticJobsMap(): ConcurrentHashMap<URI, Job> = diagnosticJobs
}
