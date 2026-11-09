package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the Groovy compilation process, handling caching,
 * async coordination, and delegation to the worker sessions.
 */
data class CompilationOrchestratorDependencies(
    val cacheService: CompilationCacheService,
    val workerSessionManager: WorkerSessionManager,
    val workspaceManager: WorkspaceManager,
    val symbolIndexer: SymbolIndexingService,
    val parseAccessor: ParseResultAccessor,
    val resultMapper: CompilationResultMapper,
    val ioDispatcher: CoroutineDispatcher,
    val errorHandler: CompilationErrorHandler,
    val semanticIndexer: SemanticIndexer? = null,
    val dependencyGraph: DependencyGraph? = null,
    /** Open editor buffers; [CompilationOrchestrator.ensureCompiled] compiles these before falling back to disk. */
    val documentProvider: DocumentProvider? = null,
    /**
     * Owns compiles started on behalf of [CompilationOrchestrator.ensureCompiled] waiters, so a cancelled request
     * stops waiting without cancelling the work other waiters share.
     */
    val waiterScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
)

class CompilationOrchestrator(dependencies: CompilationOrchestratorDependencies) {
    private val cacheService = dependencies.cacheService
    private val workerSessionManager = dependencies.workerSessionManager
    private val workspaceManager = dependencies.workspaceManager
    private val symbolIndexer = dependencies.symbolIndexer
    private val parseAccessor = dependencies.parseAccessor
    private val resultMapper = dependencies.resultMapper
    private val documentProvider = dependencies.documentProvider
    private val ioDispatcher = dependencies.ioDispatcher
    private val errorHandler = dependencies.errorHandler
    private val semanticIndexer = dependencies.semanticIndexer
    private val dependencyGraph = dependencies.dependencyGraph
    private val waiterScope = dependencies.waiterScope
    private val ownership = cacheService.ownership

    private val logger = KotlinLogging.logger {}

    /** The content hash whose degradation was last logged, per file. */
    private val loggedDegradations = ConcurrentHashMap<URI, ParseOutcome>()

    /** Where a compile's text came from, which decides what must still hold when it publishes. */
    private enum class Origin {
        /** An open editor buffer: publishes only while that same open/close lifecycle is current. */
        BUFFER,

        /** The file on disk: never publishes over a buffer opened meanwhile. */
        DISK,

        /** Text supplied by a caller for a file that is not open. */
        UNMANAGED,
    }

    /**
     * The ownership a compile takes before it reads any input: a cache/symbol ticket and a semantic publication,
     * acquired together so no invalidation can fall between them. Retired work publishes nothing, and [isValid]
     * is checked inside the same commit, so a buffer closed (or reopened) meanwhile cannot be republished.
     */
    private class Claim(
        val ticket: ArtifactOwnership.Ticket,
        val publication: SemanticIndexer.Publication?,
        val isValid: () -> Boolean,
    )

    private data class CompilationInput(val uri: URI, val content: String, val claim: Claim)

    private fun claim(uri: URI, origin: Origin): Claim {
        val (ticket, owned) = ownership.acquire(uri) {
            semanticIndexer?.begin(uri) to documentProvider?.getIncarnation(uri)
        }
        val (publication, incarnation) = owned
        val isValid: () -> Boolean = when (origin) {
            Origin.BUFFER -> {
                { incarnation != null && documentProvider?.getIncarnation(uri) === incarnation }
            }
            Origin.DISK -> {
                { documentProvider?.getSnapshot(uri) == null }
            }
            Origin.UNMANAGED -> {
                { true }
            }
        }
        return Claim(ticket, publication, isValid)
    }

    private fun originOf(uri: URI): Origin {
        val open = documentProvider?.getSnapshot(uri) != null
        return if (open) Origin.BUFFER else Origin.UNMANAGED
    }

    /**
     * Compiles Groovy source code and returns the result.
     */
    suspend fun compile(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): CompilationResult {
        val claim = claim(uri, originOf(uri))
        return claim.publication.use { compile(uri, content, compilePhase, claim) }
    }

    private suspend fun compile(uri: URI, content: String, compilePhase: Int, claim: Claim): CompilationResult {
        logger.debug { "Compiling: $uri (phase=$compilePhase)" }
        val publication = claim.publication

        return runCatching {
            // Inputs read below may already be newer than the claim; a retirement in between rejects the
            // publication, so a parse is never stored as though it used another configuration (#1184).
            // Get configuration fingerprint for cache coherency (Issue #743)
            val configFingerprint = workspaceManager.getConfigurationFingerprint()

            // Check cache first - validates both content AND configuration fingerprint
            val cachedResult = cacheService.getCached(uri, content, configFingerprint)
            if (cachedResult != null) {
                // Check for suspicious Script node
                val isSuspiciousScriptNode = parseAccessor.isSuspiciousScript(uri, cachedResult)

                if (isSuspiciousScriptNode) {
                    logger.info { "Cached result has suspicious Script node for $uri, re-compiling" }
                    performCompilation(
                        CompilationInput(uri, content, claim),
                        compilePhase,
                        configFingerprint = configFingerprint,
                    )
                } else {
                    logger.debug { "Using cached parse result for: $uri" }
                    // The symbol index may be absent or older than this result (e.g. after invalidation).
                    symbolIndexer.ensureIndexed(claim.ticket, content) { cachedResult.astModel }
                    cachedResult.ast?.takeIf { ownership.isCurrent(claim.ticket) }?.let {
                        publication?.let { token ->
                            semanticIndexer?.publish(token, content, it, cachedResult.outcome.achievedPhase)
                        }
                    }
                    resultMapper.map(cachedResult, content)
                }
            } else {
                performCompilation(
                    CompilationInput(uri, content, claim),
                    compilePhase,
                    configFingerprint = configFingerprint,
                )
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is CancellationException -> throw throwable
                is Exception -> errorHandler.handleException(throwable, uri)
                else -> throw throwable
            }
        }
    }

    private suspend fun performCompilation(
        input: CompilationInput,
        compilePhase: Int = Phases.CANONICALIZATION,
        parseMode: ParseMode = ParseMode.WORKSPACE,
        configFingerprint: String? = null,
    ): CompilationResult {
        val (uri, content, claim) = input
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Get file-specific classpath
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Use bounded workspace selection when dependency info is available (Issue #743)
        // This provides O(k) compilation instead of O(n) where k = direct deps + dependents
        val workspaceSources = getWorkspaceSourcesForCompilation(uri, logBounded = true)

        // Parse the source code; the compile aborts between phases if this coroutine is cancelled
        val checkCancelled = cancellationCheck()
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceSources,
                openSources = openSourcesFor(uri),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
                parseMode = parseMode,
                checkCancelled = checkCancelled,
            ),
        )

        logIfDegraded(uri, parseResult)

        val ast = parseResult.ast

        if (ast != null) {
            // Cache parse result with configuration fingerprint for coherency (Issue #743), and its symbol
            // index, as one commit that a retired compile cannot make (#1184)
            val fingerprint = configFingerprint ?: workspaceManager.getConfigurationFingerprint()
            val symbols = symbolIndexer.prepare(content, parseResult.astModel)
            val published = cacheService.publish(claim.ticket, content, parseResult, fingerprint, claim.isValid) {
                symbolIndexer.install(uri, symbols)
            }

            if (published) {
                // Semantic document for cross-file resolution
                claim.publication?.let {
                    semanticIndexer?.publish(it, content, ast, parseResult.outcome.achievedPhase)
                }
            } else {
                logger.debug { "Discarded the result of a retired compile of $uri" }
            }
        }

        return resultMapper.map(parseResult, content)
    }

    /**
     * Says at INFO why a file compiled without its types. Once per (uri, outcome): typing in a degraded
     * file does not repeat it, a different cause logs again, and a clean compile resets it.
     */
    private fun logIfDegraded(uri: URI, parseResult: ParseResult) {
        val outcome = parseResult.outcome
        if (!outcome.isDegraded) {
            loggedDegradations.remove(uri)
            return
        }
        if (loggedDegradations.put(uri, outcome) != outcome) {
            logger.info { outcome.describe(uri.toString()) }
        }
    }

    /**
     * Compile a transient version without updating cache.
     */
    suspend fun compileTransient(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): ParseResult {
        logger.debug { "Transient compile: $uri" }
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Use bounded workspace selection when available (Issue #743)
        val workspaceSources = getWorkspaceSourcesForCompilation(uri)
        val checkCancelled = cancellationCheck()

        return workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceSources,
                openSources = openSourcesFor(uri),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
                checkCancelled = checkCancelled,
            ),
        )
    }

    /** A hook that throws [CancellationException] once the calling coroutine is cancelled. */
    private suspend fun cancellationCheck(): () -> Unit {
        val job = currentCoroutineContext()[Job]
        return { job?.ensureActive() }
    }

    /**
     * Start or share a compatible compilation. The first creating scope owns the work: cancelling
     * another coroutine that only awaits it does not cancel it. A cancelled owner retires the job.
     */
    fun compileAsync(scope: CoroutineScope, uri: URI, content: String): Deferred<CompilationResult> {
        val identity = CompilationIdentity(
            content,
            workspaceManager.getConfigurationFingerprint(),
            // Different requested text still originated while this buffer was open.
            documentProvider?.getSnapshot(uri),
        )
        return scheduleCompilation(scope, uri, identity)
    }

    private fun scheduleCompilation(
        scope: CoroutineScope,
        uri: URI,
        identity: CompilationIdentity,
    ): Deferred<CompilationResult> {
        // Acquire before exposing queued work, without nesting semantic and registry map locks.
        val claim = claim(uri, if (identity.document != null) Origin.BUFFER else Origin.UNMANAGED)
        val publication = claim.publication
        var created = false
        var retainedPublication = false
        try {
            val deferred = cacheService.registerCompilation(uri, identity) {
                scope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                    compile(uri, identity.content, Phases.CANONICALIZATION, claim)
                }.also { created = true }
            }
            if (created) {
                deferred.invokeOnCompletion { publication?.close() }
                retainedPublication = true
            }
            deferred.start()
            return deferred
        } finally {
            if (!retainedPublication) publication?.close()
        }
    }

    /**
     * Awaits shared work, or returns null when its owner cancelled or invalidation retired it while this caller
     * is still active (a cancelled caller propagates its own cancellation). The caller then re-resolves.
     * Work this waiter starts is owned by [waiterScope], not by the request: cancelling one request (a client
     * does so on every superseded hover) does not cancel the compile its co-waiters are sharing.
     */
    private suspend fun awaitShared(uri: URI, identity: CompilationIdentity): CompilationResult? = try {
        scheduleCompilation(waiterScope, uri, identity).await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        logger.debug(e) { "Compilation of $uri was cancelled or retired; resolving its current inputs again" }
        null
    }

    /**
     * Ensures a file is compiled, either by awaiting active compilation or fetching from cache.
     * Validates configuration fingerprint for cache coherency (Issue #743).
     *
     * Shared work that is retired while this caller waits is never retried with its old inputs: the open
     * buffer, pending work, cache and disk are resolved again, so a buffer closed meanwhile is answered from
     * disk. After [MAX_RESOLUTIONS] retirements in a row the caller gets ContentModified, not a cancellation
     * it did not ask for.
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        repeat(MAX_RESOLUTIONS) {
            when (val resolution = resolveCompiled(uri)) {
                Resolution.Retired -> Unit
                is Resolution.Done -> return resolution.result
            }
        }
        throw ResponseErrorException(
            ResponseError(ResponseErrorCode.ContentModified, "Compilation of $uri kept being invalidated", null),
        )
    }

    private sealed interface Resolution {
        data object Retired : Resolution

        class Done(val result: CompilationResult?) : Resolution
    }

    @Suppress("ReturnCount") // Multiple cache and validation checks require early returns
    private suspend fun resolveCompiled(uri: URI): Resolution {
        // An open buffer is the source of truth: it may be unsaved, untitled, or newer than the file on
        // disk or the cached parse, and didOpen compiles it in a background job that may not have
        // started yet. Resolve its identity before selecting any pending job.
        documentProvider?.getSnapshot(uri)?.let { snapshot ->
            val identity =
                CompilationIdentity(snapshot.content, workspaceManager.getConfigurationFingerprint(), snapshot)
            return awaitShared(uri, identity)?.let { Resolution.Done(it) } ?: Resolution.Retired
        }

        // Get current fingerprint for cache coherency validation
        val configFingerprint = workspaceManager.getConfigurationFingerprint()

        cacheService.getPendingCompilation(uri, configFingerprint)?.let { pending ->
            return awaitShared(uri, pending.identity)?.let { Resolution.Done(it) } ?: Resolution.Retired
        }

        // A closed file's entry is retired when its buffer closes or the file changes, so it follows the disk
        cacheService.getCachedWithContent(uri, configFingerprint)?.let { (content, parseResult) ->
            logger.debug { "Using cached result for: $uri" }
            return Resolution.Done(resultMapper.mapFromCache(parseResult, content))
        }

        // Use on-demand compilation from disk if file exists
        // This handles cases where file is in symbol index but not yet opened/compiled
        val path = runCatching { Path.of(uri) }.getOrNull()
        if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
            logger.debug { "Compiling from disk on-demand: $uri" }
            // Publishes only while no buffer is open, checked inside the commit: a reopen meanwhile wins.
            val claim = claim(uri, Origin.DISK)
            return Resolution.Done(
                claim.publication.use {
                    try {
                        val content = withContext(ioDispatcher) {
                            Files.readString(path)
                        }
                        // Use MINIMAL mode for on-demand navigation requests to avoid workspace-wide recompiles
                        performCompilation(
                            CompilationInput(uri, content, claim),
                            parseMode = ParseMode.MINIMAL,
                            configFingerprint = configFingerprint,
                        )
                    } catch (e: CancellationException) {
                        throw e // the request was cancelled; not a failure to compile
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to compile from disk for $uri: ${e.message}" }
                        null
                    }
                },
            )
        }

        logger.debug { "No compilation found for $uri (not cached, not compiling, not on disk)" }
        return Resolution.Done(null)
    }

    /**
     * Gets workspace sources, using bounded selection when dependency info is available.
     * Falls back to full workspace sources for files without dependency info.
     *
     * @param uri The file being compiled
     * @param logBounded Whether to log when bounded selection is used (for debugging)
     * @return List of workspace source paths to include in compilation
     */
    private fun getWorkspaceSourcesForCompilation(uri: URI, logBounded: Boolean = false): List<Path> =
        if (dependencyGraph?.hasInfo(uri) == true) {
            val boundedUris = dependencyGraph.getCompilationSources(uri)
            if (logBounded) {
                logger.debug { "Using bounded workspace sources for $uri: ${boundedUris.size} files" }
            }
            workspaceManager.getBoundedWorkspaceSources(boundedUris)
        } else {
            workspaceManager.getWorkspaceSources()
        }

    /**
     * The other documents open in the editor, so a file compiles against their unsaved content:
     * two new files can reference each other before either is saved. The parser keeps only
     * `.groovy` sources and lets each buffer replace its on-disk copy.
     */
    private fun openSourcesFor(uri: URI): Map<URI, String> =
        documentProvider?.snapshot()?.filterKeys { it != uri }.orEmpty()

    private fun buildLocatorCandidates(uri: URI, sourcePath: Path?): Set<String> {
        val candidates = mutableSetOf<String>()
        candidates += uri.toString()
        candidates += uri.path
        sourcePath?.let { path ->
            candidates += path.toString()
            candidates += path.toAbsolutePath().toString()
        }
        return candidates
    }

    private companion object {
        /** Retirements of awaited work tolerated in a row before a caller is told the content changed. */
        const val MAX_RESOLUTIONS = 3
    }
}
