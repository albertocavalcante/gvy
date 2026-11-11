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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
import java.util.concurrent.atomic.AtomicLong
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
    private data class Runtime(
        val configuration: ServerConfiguration,
        val service: DiagnosticsService,
        val generation: Long = 0,
    )

    private class Run(val uri: URI, val snapshot: DocumentSnapshot, val settings: Runtime, val id: Long) {
        // No parent: cancelling an individual waiter must not cancel shared analysis or other waiters.
        val result = CompletableDeferred<DiagnosticsRunResult>()

        fun invalidate() {
            result.completeExceptionally(DiagnosticsRunUnavailableException("Diagnostics run $id is obsolete for $uri"))
        }
    }

    private val runs = ConcurrentHashMap<URI, Run>()
    private val runIds = AtomicLong()

    private val publicationLock = Any()

    @Volatile
    private var runtime = Runtime(serverConfiguration, diagnosticsService)

    /** Replace provider configuration and cancel work computed under the previous settings. */
    fun updateConfiguration(configuration: ServerConfiguration, service: DiagnosticsService) {
        val (retiredJobs, retiredRuns) = synchronized(publicationLock) {
            runtime = Runtime(configuration, service, Math.incrementExact(runtime.generation))
            (diagnosticJobs.values.toList() to runs.values.toList()).also { runs.clear() }
        }
        // Completion/cancellation handlers may reenter lifecycle operations. Invoke them outside monitors.
        retiredRuns.forEach { it.invalidate() }
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
    // The owned job records infrastructure failure; cancellation and Errors propagate.
    @Suppress("TooGenericExceptionCaught")
    fun trigger(uri: URI, content: String, debounce: Boolean = false) {
        val captured = documentProvider.getSnapshot(uri)?.takeIf { it.content == content } ?: return
        val run = Run(uri, captured, runtime, runIds.incrementAndGet())
        // Register before starting; a fast completion must not leave a completed job in the active map.
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext.job
            try {
                if (debounce && debounceMillis > 0) delay(debounceMillis)
                if (!isCurrentRun(run, currentJob)) return@launch
                computeDiagnostics(this, run, currentJob)
            } catch (cancelled: CancellationException) {
                run.invalidate()
                throw cancelled
            } catch (obsolete: DiagnosticsRunUnavailableException) {
                run.result.completeExceptionally(obsolete)
            } catch (failure: Exception) {
                run.result.completeExceptionally(failure)
                logFailure(uri, failure)
            } catch (fatal: Error) {
                run.result.completeExceptionally(fatal)
                throw fatal
            } finally {
                diagnosticJobs.remove(uri, currentJob)
            }
        }

        var previousJob: Job? = null
        var previousRun: Run? = null
        var registered = false
        documentProvider.withCurrentSnapshot(uri, captured) {
            synchronized(publicationLock) {
                if (runtime === run.settings) {
                    previousJob = diagnosticJobs.put(uri, job)
                    previousRun = runs.put(uri, run)
                    registered = true
                }
            }
        }
        if (!registered) {
            run.invalidate()
            job.cancel()
            return
        }
        // A lazy job may be cancelled before its body (including finally) ever executes.
        job.invokeOnCompletion { failure ->
            if (failure == null || failure is CancellationException) {
                run.invalidate()
            } else {
                run.result.completeExceptionally(failure)
            }
            diagnosticJobs.remove(uri, job)
        }

        previousRun?.invalidate()
        previousJob?.cancel()
        job.start()
    }

    private suspend fun computeDiagnostics(scope: CoroutineScope, run: Run, owner: Job) {
        val result = compileCurrent(scope, run.uri, run.snapshot, owner, run.settings)
        scope.ensureActive()
        val parserEnabled = run.settings.configuration.diagnosticConfig.isProviderEnabled(
            "parser",
            enabledByDefault = true,
        )
        val parserDiagnostics = if (parserEnabled) result.diagnostics else emptyList()
        checkCurrentRun(publishDiagnostics(run.uri, run.snapshot, owner, parserDiagnostics, run.settings)) {
            "Parser publication is no longer current for ${run.uri}"
        }

        // A worker/IO exception is not completed parsing, even when parser diagnostics are disabled.
        // Keep its ordinary parser publication, but never certify it as a settled analysis result.
        check(result.parseCompleted) { "Parsing did not complete for ${run.uri}" }
        val collected = run.settings.service.collectDiagnostics(run.uri, run.snapshot.content, result)
        val allDiagnostics = parserDiagnostics + collected.diagnostics
        scope.ensureActive()
        if (collected.diagnostics.isNotEmpty()) {
            checkCurrentRun(publishDiagnostics(run.uri, run.snapshot, owner, allDiagnostics, run.settings)) {
                "Final publication is no longer current for ${run.uri}"
            }
        }
        checkCurrentRun(isCurrentRun(run, owner)) {
            "Completed diagnostics are obsolete for ${run.uri}"
        }
        val cohort = (if (parserEnabled) listOf("parser") else emptyList()) + collected.providerIds
        run.result.complete(
            DiagnosticsRunResult(
                run.uri.toString(),
                run.snapshot.version,
                run.id,
                run.settings.generation,
                allDiagnostics.take(run.settings.configuration.maxNumberOfProblems.coerceAtLeast(0)),
                cohort,
                collected.failures,
            ),
        )
        logger.debug { "Completed diagnostics run ${run.id} for ${run.uri}" }
    }

    private fun logFailure(uri: URI, failure: Exception) {
        when (failure) {
            is CompilationFailedException -> logger.error(failure) { "Compilation failed for: $uri" }
            is IllegalArgumentException -> logger.error(failure) { "Invalid arguments for: $uri" }
            is IOException -> logger.error(failure) { "I/O error for: $uri" }
            else -> logger.error(failure) { "Unexpected error during diagnostics for: $uri" }
        }
    }

    private fun isCurrentRun(run: Run, owner: Job? = null): Boolean {
        var current = false
        documentProvider.withCurrentSnapshot(run.uri, run.snapshot) {
            synchronized(publicationLock) {
                current = runtime === run.settings && runs[run.uri] === run &&
                    (owner == null || owner.isActive && diagnosticJobs[run.uri] === owner)
            }
        }
        return current
    }

    /**
     * Waits for a terminal result retained for this exact current buffer state. Completed runs remain observable.
     * A caller's cancellation affects only its wait. Revalidate after suspension against edit/reopen/configuration.
     */
    suspend fun awaitCurrentDiagnostics(uri: URI, version: Int? = null): DiagnosticsRunResult {
        val run = runs[uri]
            ?: throw DiagnosticsRunUnavailableException("No current diagnostics run for $uri")
        checkCurrentRun((version == null || run.snapshot.version == version) && isCurrentRun(run)) {
            "Requested diagnostics are not current for $uri at version $version"
        }
        val result = run.result.await()
        currentCoroutineContext().ensureActive()
        checkCurrentRun(isCurrentRun(run)) {
            "Diagnostics changed while awaiting $uri"
        }
        return result
    }

    private inline fun checkCurrentRun(current: Boolean, message: () -> String) {
        if (!current) throw DiagnosticsRunUnavailableException(message())
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
        val (retiredJob, retiredRun) = synchronized(publicationLock) {
            diagnosticJobs.remove(uri) to runs.remove(uri)
        }
        retiredRun?.invalidate()
        retiredJob?.cancel()
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
        var retiredRun: Run? = null
        try {
            documentProvider.removeIfCurrent(uri, captured) {
                // Lock order is document lifecycle -> publication; client lookup/cancellation are outside both.
                synchronized(publicationLock) {
                    retired = diagnosticJobs.remove(uri)
                    retiredRun = runs.remove(uri)
                    recipient?.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), emptyList()))
                }
            }
        } finally {
            retiredRun?.invalidate()
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
