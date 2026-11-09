package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Quiet period after the last edit before a debounced diagnostics run starts compiling. */
const val DEFAULT_DIAGNOSTICS_DEBOUNCE_MS = 250L

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
@Suppress("LongParameterList") // Injected collaborators plus the test-overridable debounce
class DiagnosticsOrchestrator(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    diagnosticsService: DiagnosticsService,
    private val documentProvider: DocumentProvider,
    serverConfiguration: ServerConfiguration,
    private val client: () -> LanguageClient?,
    private val debounceMillis: Long = DEFAULT_DIAGNOSTICS_DEBOUNCE_MS,
) {
    private val logger = KotlinLogging.logger {}
    private data class Runtime(val configuration: ServerConfiguration, val service: DiagnosticsService)

    private val publicationLock = Any()

    @Volatile
    private var runtime = Runtime(serverConfiguration, diagnosticsService)

    /** Replace provider configuration and cancel work computed under the previous settings. */
    fun updateConfiguration(configuration: ServerConfiguration, service: DiagnosticsService) {
        val retiredJobs = synchronized(publicationLock) {
            runtime = Runtime(configuration, service)
            diagnosticJobs.values.toList()
        }
        // Cancellation handlers may reenter document lifecycle operations. Never invoke them under a monitor.
        retiredJobs.forEach { it.cancel() }
        refreshAll()
    }

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
     * @param debounce When true (used for didChange), wait [debounceMillis] before compiling so a burst
     *   of edits results in a single compile of the latest content. Open/save/refresh run immediately.
     */
    @Suppress("TooGenericExceptionCaught")
    fun trigger(uri: URI, content: String, debounce: Boolean = false) {
        val captured = documentProvider.getSnapshot(uri)?.takeIf { it.content == content } ?: return
        // Create the job lazily so it is registered (and any previous job cancelled) before it starts.
        // Otherwise a fast job could finish and run its cleanup before registration, leaving a
        // completed job in the map, and the new job could run while the old one is still active.
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext.job
            try {
                runCatching {
                    // A newer trigger cancels this job during the delay, before any expensive work starts.
                    if (debounce && debounceMillis > 0) delay(debounceMillis)
                    if (documentProvider.getSnapshot(uri) !== captured) return@runCatching

                    val settings = runtime
                    // Use compileAsync for proper coordination
                    val result = compileCurrent(this, uri, captured, currentJob, settings)

                    ensureActive() // Ensure job wasn't cancelled before publishing

                    val parserEnabled = settings.configuration.diagnosticConfig.isProviderEnabled(
                        "parser",
                        enabledByDefault = true,
                    )
                    val parserDiagnostics = if (parserEnabled) result.diagnostics else emptyList()

                    // Publish compilation diagnostics first to keep UX responsive.
                    // NOTE: Tradeoff (See #564):
                    // This can result in two diagnostics publications (compile first, then provider merge),
                    // but avoids blocking syntax feedback on slow lint initialization.
                    if (!publishDiagnostics(uri, captured, currentJob, parserDiagnostics, settings)) return@runCatching

                    val extraDiagnostics = settings.service.getDiagnostics(uri, content)
                    val allDiagnostics = parserDiagnostics + extraDiagnostics

                    ensureActive()
                    if (extraDiagnostics.isNotEmpty()) {
                        publishDiagnostics(uri, captured, currentJob, allDiagnostics, settings)
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

        var previous: Job? = null
        val registered = documentProvider.withCurrentSnapshot(uri, captured) {
            previous = diagnosticJobs.put(uri, job)
        }
        if (!registered) {
            job.cancel()
            return
        }
        previous?.cancel()
        job.start()
    }

    /** A shared compile may belong to another request; only a still-current waiter may retry it once. */
    private suspend fun compileCurrent(
        scope: CoroutineScope,
        uri: URI,
        captured: DocumentSnapshot,
        owner: Job,
        settings: Runtime,
    ): CompilationResult {
        try {
            return compilationService.compileAsync(scope, uri, captured.content).await()
        } catch (cancelled: CancellationException) {
            // An external owner can cancel its Deferred without cancelling this diagnostics job.
            // Own cancellation must propagate, and obsolete work must never be restarted.
            scope.ensureActive()
            if (documentProvider.getSnapshot(uri) !== captured || runtime !== settings ||
                diagnosticJobs[uri] !== owner
            ) {
                throw cancelled
            }
            logger.debug { "Retrying current diagnostics after shared compilation was cancelled for $uri" }
            return compilationService.compileAsync(scope, uri, captured.content).await()
        }
    }

    /**
     * Cancels and removes any running diagnostic job for the specified URI.
     *
     * @param uri The document URI
     */
    fun cancelAndRemove(uri: URI) {
        val retired = synchronized(publicationLock) { diagnosticJobs.remove(uri) }
        retired?.cancel()
    }

    /** Close and clear the captured incarnation, with the same linearization boundary as publication. */
    fun close(uri: URI) {
        val captured = documentProvider.getIncarnation(uri) ?: return
        var recipient: LanguageClient? = null
        try {
            recipient = client()
        } finally {
            // A failing client lookup must not leave the buffer open. A reentrant reopen must survive.
            closeDocument(uri, captured, recipient)
        }
    }

    private fun closeDocument(uri: URI, captured: DocumentIncarnation, recipient: LanguageClient?) {
        var retired: Job? = null
        try {
            documentProvider.removeIfCurrent(uri, captured) {
                // Lock order is document lifecycle -> publication; client lookup/cancellation are outside both.
                synchronized(publicationLock) {
                    retired = diagnosticJobs.remove(uri)
                    recipient?.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), emptyList()))
                }
            }
        } finally {
            retired?.cancel()
        }
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
    private fun publishDiagnostics(
        uri: URI,
        captured: DocumentSnapshot,
        owner: Job,
        diagnostics: List<org.eclipse.lsp4j.Diagnostic>,
        settings: Runtime,
    ): Boolean {
        // Resolving the client can reenter change/close/configuration handling; validate afterward.
        val recipient = client() ?: return false
        var published = false
        documentProvider.withCurrentSnapshot(uri, captured) {
            synchronized(publicationLock) {
                if (runtime !== settings || !owner.isActive || diagnosticJobs[uri] !== owner) return@synchronized
                val limited = diagnostics.take(settings.configuration.maxNumberOfProblems.coerceAtLeast(0))
                logger.debug { "Publishing ${limited.size} diagnostics for $uri" }
                recipient.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), limited, captured.version))
                published = true
            }
        }
        return published
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
