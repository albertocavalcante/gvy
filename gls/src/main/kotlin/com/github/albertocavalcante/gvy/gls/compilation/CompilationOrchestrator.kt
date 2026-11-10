package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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

    /**
     * [incarnation]: for [Origin.BUFFER], the lifecycle the text was read from, captured with that text.
     * [snapshot]: that buffer state, when known; its version orders this compile's writes (#1383).
     */
    private fun claim(
        uri: URI,
        origin: Origin,
        incarnation: DocumentIncarnation? = null,
        snapshot: DocumentSnapshot? = null,
    ): Claim {
        val version = snapshot?.version
        val order = if (origin == Origin.BUFFER && incarnation != null && version != null) {
            BufferOrder(incarnation, version) { documentProvider?.getSnapshot(uri) === snapshot }
        } else {
            null
        }
        val (ticket, publication) = ownership.acquire(uri, order) { semanticIndexer?.begin(uri) }
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

    /** Stops compiles started for waiters (server shutdown); later waiters fail fast. */
    fun shutdown() {
        waiterScope.cancel()
    }

    /**
     * Compiles Groovy source code and returns the result.
     */
    suspend fun compile(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): CompilationResult {
        val buffer = documentProvider?.getBuffer(uri)
        val incarnation = buffer?.incarnation
        // Ordered only when the caller's text is the buffer's: other text has no version of its own
        val snapshot = buffer?.snapshot?.takeIf { it.content == content }
        val claim = claim(uri, if (incarnation != null) Origin.BUFFER else Origin.UNMANAGED, incarnation, snapshot)
        return claim.publication.use { compile(uri, content, compilePhase, claim) }
    }

    private suspend fun compile(uri: URI, content: String, compilePhase: Int, claim: Claim): CompilationResult {
        logger.debug { "Compiling: $uri (phase=$compilePhase)" }
        val publication = claim.publication

        return runCatching {
            // One workspace model supplies this compile's key and inputs (H1). It may already be newer than
            // the claim; a retirement in between rejects the publication (#1184).
            val workspace = workspaceManager.current()
            // This document's classpath and key from that one model: a selected target's own (#1235)
            val context = workspaceManager.compileContextFor(uri, content, workspace)
            val configFingerprint = context.fingerprint

            // Check cache first - validates both content AND configuration fingerprint
            val cachedResult = cacheService.getCached(uri, content, configFingerprint)
            if (cachedResult != null) {
                // Check for suspicious Script node
                val isSuspiciousScriptNode = parseAccessor.isSuspiciousScript(uri, cachedResult)

                if (isSuspiciousScriptNode) {
                    logger.info { "Cached result has suspicious Script node for $uri, re-compiling" }
                    performCompilation(
                        CompilationInput(uri, content, claim),
                        workspace,
                        compilePhase,
                        context = context,
                    )
                } else {
                    logger.debug { "Using cached parse result for: $uri" }
                    // The symbol index may be absent or older than this result (e.g. after invalidation).
                    // Semantics follow the symbol commit: published only when this ticket's index is current
                    val indexed = symbolIndexer.ensureIndexed(claim.ticket, content) { cachedResult.astModel } != null
                    cachedResult.ast?.takeIf { indexed }?.let {
                        publication?.let { token ->
                            semanticIndexer?.publish(token, content, it, cachedResult.outcome.achievedPhase)
                        }
                    }
                    resultMapper.map(cachedResult, content)
                }
            } else {
                performCompilation(CompilationInput(uri, content, claim), workspace, compilePhase, context = context)
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
        workspace: WorkspaceModel,
        compilePhase: Int = Phases.CANONICALIZATION,
        parseMode: ParseMode = ParseMode.WORKSPACE,
        context: DocumentCompileContext? = null,
    ): CompilationResult {
        val (uri, content, claim) = input
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // File-specific classpath and its cache key, from the same workspace as roots and sources (#1235)
        val compileContext = context ?: workspaceManager.compileContextFor(uri, content, workspace)
        val classpath = compileContext.classpath

        // Use bounded workspace selection when dependency info is available (Issue #743)
        // This provides O(k) compilation instead of O(n) where k = direct deps + dependents
        val workspaceSources = getWorkspaceSourcesForCompilation(uri, workspace, logBounded = true)

        // Parse the source code; the compile aborts between phases if this coroutine is cancelled
        val checkCancelled = cancellationCheck()
        val locator = symbolIndexer.classLocator(sourcePath)
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspace.sourceRoots,
                workspaceSources = workspaceSources,
                openSources = openSourcesFor(uri),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
                parseMode = parseMode,
                checkCancelled = checkCancelled,
                // MINIMAL adds no workspace sources; the class index still finds a class declared in a file
                // named otherwise, one file per name the resolver asks for (#1425).
                workspaceClassLocator = locator.takeIf { parseMode == ParseMode.MINIMAL },
            ),
        )

        logIfDegraded(uri, parseResult)

        val ast = parseResult.ast

        if (ast != null) {
            // Cache parse result with configuration fingerprint for coherency (Issue #743), and its symbol
            // index, as one commit that a retired compile cannot make (#1184)
            val fingerprint = compileContext.fingerprint
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
        val workspace = workspaceManager.current()
        val classpath = workspaceManager.getClasspathForFile(uri, content, workspace)

        // Use bounded workspace selection when available (Issue #743)
        val workspaceSources = getWorkspaceSourcesForCompilation(uri, workspace)
        val checkCancelled = cancellationCheck()

        return workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspace.sourceRoots,
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
        val buffer = documentProvider?.getBuffer(uri)
        val identity = CompilationIdentity(
            content,
            workspaceManager.fingerprintFor(uri),
            // Different requested text still originated while this buffer was open.
            buffer?.snapshot,
            buffer?.incarnation,
        )
        return scheduleCompilation(scope, uri, identity).deferred
    }

    private fun scheduleCompilation(
        scope: CoroutineScope,
        uri: URI,
        identity: CompilationIdentity,
    ): PendingCompilation {
        // Acquire before exposing queued work, without nesting semantic and registry map locks.
        val incarnation = identity.incarnation
        val claim = claim(
            uri,
            if (incarnation != null) Origin.BUFFER else Origin.UNMANAGED,
            incarnation,
            identity.document,
        )
        val publication = claim.publication
        var created = false
        var retainedPublication = false
        try {
            val pending = cacheService.registerPending(uri, identity, claim.ticket) {
                scope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                    compile(uri, identity.content, Phases.CANONICALIZATION, claim)
                }.also { created = true }
            }
            val deferred = pending.deferred
            if (created) {
                deferred.invokeOnCompletion { publication?.close() }
                retainedPublication = true
            }
            deferred.start()
            return pending
        } finally {
            if (!retainedPublication) publication?.close()
        }
    }

    /**
     * Awaits shared work. A cancelled caller propagates its own cancellation; otherwise a job that ended by
     * cancellation is classified, and the caller re-resolves. Work this waiter starts is owned by
     * [waiterScope], not by the request: cancelling one request (a client does so on every superseded hover)
     * does not cancel the compile its co-waiters are sharing.
     */
    private suspend fun awaitShared(uri: URI, identity: CompilationIdentity): Awaited {
        val pending = scheduleCompilation(waiterScope, uri, identity)
        val cause = try {
            // A retirement wakes this waiter at once, not after the stale compile returns (#1429)
            pending.awaitUnlessRetired()?.let { return Awaited.Done(it) }
            null
        } catch (e: CancellationException) {
            e
        }
        // Woken by the retirement signal or by the job's cancellation: both are classified alike (#1390)
        currentCoroutineContext().ensureActive()
        ensureRunning()
        val documentUnchanged = documentProvider?.getSnapshot(uri) === identity.document
        val ownerCancelled = ownership.isCurrent(pending.ticket) && documentUnchanged
        logger.debug(cause) {
            "Compilation of $uri was ${if (ownerCancelled) "cancelled by its owner" else "retired"}; " +
                "resolving its current inputs again"
        }
        return if (ownerCancelled) Awaited.OwnerCancelled else Awaited.Retired
    }

    /**
     * Ensures a file is compiled, either by awaiting active compilation or fetching from cache.
     * Validates configuration fingerprint for cache coherency (Issue #743).
     *
     * Shared work that is retired while this caller waits is never retried with its old inputs: the open
     * buffer, pending work, cache and disk are resolved again, so a buffer closed meanwhile is answered from
     * disk. After [MAX_RESOLUTIONS] retirements the caller gets ContentModified, not a cancellation it did
     * not ask for; cancellations by the shared job's owner alone do not count toward that bound (#1343).
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        ensureRunning()
        return resolveBounded(uri) { resolveCompiled(uri) }
    }

    /**
     * Compiles [snapshot], a buffer the caller read itself, under the same policy as [ensureCompiled]: shared
     * work retired meanwhile is retried with the newest buffer text this service knows, never leaking a
     * cancellation the caller did not ask for, and ends in ContentModified after [MAX_RESOLUTIONS] attempts.
     */
    suspend fun ensureCompiled(uri: URI, snapshot: DocumentSnapshot): CompilationResult {
        ensureRunning()
        var current = snapshot
        val result = resolveBounded(uri) {
            val buffer = documentProvider?.getBuffer(uri)?.takeIf { it.snapshot === current }
            val identity = CompilationIdentity(
                current.content,
                workspaceManager.fingerprintFor(uri),
                current,
                buffer?.incarnation,
            )
            awaitShared(uri, identity).also { outcome ->
                if (outcome !is Awaited.Done) documentProvider?.getSnapshot(uri)?.let { current = it }
            }
        }
        return checkNotNull(result) { "Shared compilation of $uri completed without a result" }
    }

    /** After [shutdown] waiters are cancelled at once instead of re-resolving into a stopped scope. */
    private fun ensureRunning() {
        if (!waiterScope.isActive) throw CancellationException("The compilation service is shut down")
    }

    @Suppress("ReturnCount") // Multiple cache and validation checks require early returns
    private suspend fun resolveCompiled(uri: URI): Awaited {
        // An open buffer is the source of truth: it may be unsaved, untitled, or newer than the file on
        // disk or the cached parse, and didOpen compiles it in a background job that may not have
        // started yet. Resolve its identity before selecting any pending job.
        documentProvider?.getBuffer(uri)?.let { buffer ->
            val identity = CompilationIdentity(
                buffer.snapshot.content,
                workspaceManager.fingerprintFor(uri),
                buffer.snapshot,
                buffer.incarnation,
            )
            return awaitShared(uri, identity)
        }

        // One model for this resolution: the fingerprint validates the cache and keys any disk compile
        val workspace = workspaceManager.current()
        val configFingerprint = workspaceManager.fingerprintFor(uri, workspace)

        cacheService.getPendingCompilation(uri, configFingerprint)?.let { pending ->
            return awaitShared(uri, pending.identity)
        }

        // A closed file's entry is retired when its buffer closes or the file changes, so it follows the disk
        cacheService.getCachedWithContent(uri, configFingerprint)?.let { (content, parseResult) ->
            logger.debug { "Using cached result for: $uri" }
            return Awaited.Done(resultMapper.mapFromCache(parseResult, content))
        }

        // Use on-demand compilation from disk if file exists
        // This handles cases where file is in symbol index but not yet opened/compiled
        val path = runCatching { Path.of(uri) }.getOrNull()
        if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
            logger.debug { "Compiling from disk on-demand: $uri" }
            // Publishes only while no buffer is open, checked inside the commit: a reopen meanwhile wins.
            val claim = claim(uri, Origin.DISK)
            // Read the model again now the ticket is taken, as the buffer path does: a change published
            // before this read retires the ticket, so a compile on an older model cannot publish (#1422).
            val compileWorkspace = workspaceManager.current()
            return Awaited.Done(
                claim.publication.use {
                    try {
                        val content = withContext(ioDispatcher) {
                            Files.readString(path)
                        }
                        // Use MINIMAL mode for on-demand navigation requests to avoid workspace-wide recompiles
                        performCompilation(
                            CompilationInput(uri, content, claim),
                            compileWorkspace,
                            parseMode = ParseMode.MINIMAL,
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
        return Awaited.Done(null)
    }

    /**
     * Gets workspace sources, using bounded selection when dependency info is available.
     * Falls back to full workspace sources for files without dependency info.
     *
     * @param uri The file being compiled
     * @param logBounded Whether to log when bounded selection is used (for debugging)
     * @return List of workspace source paths to include in compilation
     */
    private fun getWorkspaceSourcesForCompilation(
        uri: URI,
        workspace: WorkspaceModel,
        logBounded: Boolean = false,
    ): List<Path> = if (dependencyGraph?.hasInfo(uri) == true) {
        val boundedUris = dependencyGraph.getCompilationSources(uri)
        if (logBounded) {
            logger.debug { "Using bounded workspace sources for $uri: ${boundedUris.size} files" }
        }
        workspaceManager.getBoundedWorkspaceSources(boundedUris, workspace)
    } else {
        workspace.sources
    }

    /**
     * The other documents open in the editor, so a file compiles against their unsaved content:
     * two new files can reference each other before either is saved. The parser keeps only
     * `.groovy` sources and lets each buffer replace its on-disk copy.
     */
    private fun openSourcesFor(uri: URI): Map<URI, String> =
        documentProvider?.snapshot()?.filterKeys { it != uri }.orEmpty()
}

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

private fun contentModified(uri: URI) = ResponseErrorException(
    ResponseError(ResponseErrorCode.ContentModified, "Compilation of $uri kept being invalidated", null),
)

/** Retirements of awaited work tolerated before a caller is told the content changed. */
private const val MAX_RESOLUTIONS = 3

/** Owner cancellations re-resolved without counting retirements, bounded so no waiter loops forever (#1343). */
private const val MAX_OWNER_CANCELLATIONS = 10

private val boundedLogger = KotlinLogging.logger {}

/**
 * Runs [attempt] until it answers. Retirements count toward [MAX_RESOLUTIONS]; owner cancellations re-resolve
 * without counting, up to [MAX_OWNER_CANCELLATIONS]. Either bound ends in ContentModified, and every round
 * first checks that the caller's own request is still active.
 */
private suspend fun resolveBounded(uri: URI, attempt: suspend () -> Awaited): CompilationResult? {
    var retirements = 0
    var ownerCancellations = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        when (val outcome = attempt()) {
            is Awaited.Done -> return outcome.result
            Awaited.Retired -> if (++retirements >= MAX_RESOLUTIONS) throw contentModified(uri)
            Awaited.OwnerCancelled -> if (++ownerCancellations >= MAX_OWNER_CANCELLATIONS) {
                boundedLogger.debug {
                    "$uri: owner cancelled the shared compile $ownerCancellations times; reporting ContentModified"
                }
                throw contentModified(uri)
            }
        }
    }
}

/** How awaiting shared work ended for a waiter that is itself still active. */
private sealed interface Awaited {
    /** An answer: shared work's result, or a cache, disk or no-file resolution ([result] may be null). */
    class Done(val result: CompilationResult?) : Awaited

    /** Invalidation or a configuration change made the job's ticket stale, or the waiter's document changed. */
    data object Retired : Awaited

    /**
     * The job's owner cancelled it (a superseded diagnostics run, for example) while its ticket stayed
     * current and the waiter's document did not change: nothing the waiter depends on moved (#1343).
     */
    data object OwnerCancelled : Awaited
}

/**
 * Files declaring the top-level workspace class a [ParseRequest.workspaceClassLocator] names, from the symbol
 * index: an exact qualified-name match only, so a same-named class in another package is never added, and never
 * [self]. Before startup indexing has run the index is empty and nothing is found, as before #1425.
 */
private fun SymbolIndexingService.classLocator(self: Path?): (String) -> List<Path> = { fullyQualifiedName ->
    findClasses(ClassNameQuery.Exact(fullyQualifiedName.substringAfterLast('.')))
        .filter { it.node.outerClass == null && it.fullyQualifiedName == fullyQualifiedName }
        .mapNotNull { runCatching { Path.of(it.uri) }.getOrNull() }
        .filter { it != self }
        .distinct()
}
