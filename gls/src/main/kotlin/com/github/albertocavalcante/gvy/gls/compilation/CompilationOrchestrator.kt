// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KLogger
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
    /** Listed copies an open buffer under another spelling stands for (#1446): not compiled beside it. */
    val shadowed: () -> Set<URI> = { emptySet() },
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
    private val shadowed = dependencies.shadowed
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
        val input = CompilationInput(uri, content, claim)
        return runCatching {
            // The cache key and all compile inputs come from one captured workspace and source policy.
            val workspace = workspaceManager.current()
            val context = workspaceManager.compileContextFor(uri, content, workspace)
            requireCurrentSourcePolicy(context.sourcePolicy, uri)
            val cached = cacheService.getCached(uri, content, context.fingerprint)
            if (cached == null || parseAccessor.isSuspiciousScript(uri, cached)) {
                if (cached != null) logger.info { "Cached result has suspicious Script node for $uri, re-compiling" }
                performCompilation(input, workspace, compilePhase, context = context)
            } else {
                reuseCachedResult(input, cached, context)
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is CancellationException, is ResponseErrorException -> throw throwable
                is Exception -> errorHandler.handleException(throwable, uri)
                else -> throw throwable
            }
        }
    }

    private fun reuseCachedResult(
        input: CompilationInput,
        cached: ParseResult,
        context: DocumentCompileContext,
    ): CompilationResult {
        val (uri, content, claim) = input
        val policy = context.sourcePolicy
        logger.debug { "Using cached parse result for: $uri" }
        // Semantics follow the symbol commit; a retired source policy can publish neither one.
        val indexed = symbolIndexer.ensureIndexed(
            claim.ticket,
            content,
            isValid = { policy?.isCurrent() != false },
            publish = { commit -> publishWithinSourcePolicy(policy, commit) },
        ) { cached.astModel } != null
        cached.ast?.takeIf { indexed }?.let { ast ->
            claim.publication?.let { publication ->
                semanticIndexer?.publishIfCurrent(
                    publication,
                    SemanticContent(content, ast, cached.outcome.achievedPhase),
                ) { commit -> publishWithinSourcePolicy(policy, commit) }
            }
        }
        requireCurrentSourcePolicy(policy, uri)
        return resultMapper.map(cached, content).copy(
            sourceMembershipKnown = context.sourceMembershipKnown,
            sourceVisibilityKnown = context.sourceVisibilityKnown,
        )
    }

    private suspend fun performCompilation(
        input: CompilationInput,
        workspace: WorkspaceModel,
        compilePhase: Int = Phases.CANONICALIZATION,
        parseMode: ParseMode = ParseMode.WORKSPACE,
        context: DocumentCompileContext? = null,
    ): CompilationResult {
        val (uri, content) = input
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // File-specific classpath and its cache key, from the same workspace as roots and sources (#1235)
        val compileContext = context ?: workspaceManager.compileContextFor(uri, content, workspace)
        val classpath = compileContext.classpath

        // Use bounded workspace selection when dependency info is available (Issue #743)
        // This provides O(k) compilation instead of O(n) where k = direct deps + dependents
        val policy = compileContext.sourcePolicy
        val workspaceSources = getWorkspaceSourcesForCompilation(uri, workspace, policy, logBounded = true)

        // Parse the source code; the compile aborts between phases if this coroutine is cancelled
        val checkCancelled = cancellationCheck()
        val sourceFilter = if (policy == null) {
            workspaceManager.sourceFilterFor(uri, workspace)
        } else {
            policy.sourceFilter()
        }
        val (openSources, locatorBuffers) = openSourcesFor(uri, workspace, policy)
        val locate = symbolIndexer.classLocator(sourcePath)
        val locator = { name: String -> locate(name).filter { sourceFilter == null || sourceFilter(it) } }
        val request = ParseRequest(
            uri = uri,
            content = content,
            classpath = classpath,
            sourceRoots = policy?.sourceRoots() ?: workspaceManager.sourceRootsFor(uri, workspace),
            workspaceSources = workspaceSources,
            openSources = openSources,
            locatorCandidates = buildLocatorCandidates(uri, sourcePath),
            compilePhase = compilePhase,
            parseMode = parseMode,
            checkCancelled = checkCancelled,
            // Locate unknown dependency sources on demand, including task-added files outside roots.
            workspaceClassLocator = locator,
        )
        val parseResult = workerSessionManager.parseWithSourcePolicy(request, sourceFilter, locatorBuffers)

        if (policy?.isCurrent() == false) throw contentModified(uri)
        logIfDegraded(uri, parseResult, loggedDegradations, logger)

        installParsedResult(input, parseResult, compileContext)

        if (policy?.isCurrent() == false) throw contentModified(uri)
        return resultMapper.map(parseResult, content).copy(
            sourceMembershipKnown = compileContext.sourceMembershipKnown,
            sourceVisibilityKnown = compileContext.sourceVisibilityKnown,
        )
    }

    private fun installParsedResult(
        input: CompilationInput,
        parseResult: ParseResult,
        compileContext: DocumentCompileContext,
    ) {
        val (uri, content, claim) = input
        val policy = compileContext.sourcePolicy
        val ast = parseResult.ast

        if (ast != null) {
            // Cache parse result with configuration fingerprint for coherency (Issue #743), and its symbol
            // index, as one commit that a retired compile cannot make (#1184)
            val fingerprint = compileContext.fingerprint
            val symbols = symbolIndexer.prepare(content, parseResult.astModel)
            var published = false
            val install = {
                published = cacheService.publish(claim.ticket, content, parseResult, fingerprint, { claim.isValid() }) {
                    symbolIndexer.install(uri, symbols)
                }
            }
            if (policy == null) install() else policy.publishIfCurrent(install)

            if (published) {
                // Semantic document for cross-file resolution
                claim.publication?.let {
                    semanticIndexer?.publishIfCurrent(
                        it,
                        SemanticContent(content, ast, parseResult.outcome.achievedPhase),
                    ) { commit -> publishWithinSourcePolicy(policy, commit) }
                }
            } else {
                logger.debug { "Discarded the result of a retired compile of $uri" }
            }
        }
    }

    /**
     * Compile a transient version without updating cache.
     */
    suspend fun compileTransient(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): ParseResult {
        logger.debug { "Transient compile: $uri" }
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()
        val workspace = workspaceManager.current()
        val context = workspaceManager.compileContextFor(uri, content, workspace)
        val policy = context.sourcePolicy
        val classpath = context.classpath

        // Use bounded workspace selection when available (Issue #743)
        val workspaceSources = getWorkspaceSourcesForCompilation(uri, workspace, policy)
        val checkCancelled = cancellationCheck()
        val sourceFilter = if (policy == null) {
            workspaceManager.sourceFilterFor(uri, workspace)
        } else {
            policy.sourceFilter()
        }
        val (openSources, locatorBuffers) = openSourcesFor(uri, workspace, policy)
        val locate = symbolIndexer.classLocator(sourcePath)
        val locator = { name: String -> locate(name).filter { sourceFilter == null || sourceFilter(it) } }

        val request = ParseRequest(
            uri = uri,
            content = content,
            classpath = classpath,
            sourceRoots = policy?.sourceRoots() ?: workspaceManager.sourceRootsFor(uri, workspace),
            workspaceSources = workspaceSources,
            openSources = openSources,
            locatorCandidates = buildLocatorCandidates(uri, sourcePath),
            compilePhase = compilePhase,
            checkCancelled = checkCancelled,
            workspaceClassLocator = locator,
        )
        val result = workerSessionManager.parseWithSourcePolicy(request, sourceFilter, locatorBuffers)
        if (policy?.isCurrent() == false) throw contentModified(uri)
        return result
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
            return Awaited.Done(
                resultMapper.mapFromCache(parseResult, content)?.let { cached ->
                    val context = workspaceManager.compileContextFor(uri, content, workspace)
                    cached.copy(
                        sourceMembershipKnown = context.sourceMembershipKnown,
                        sourceVisibilityKnown = context.sourceVisibilityKnown,
                    )
                },
            )
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
        policy: SourcePolicySnapshot?,
        logBounded: Boolean = false,
    ): List<Path> {
        val sources = if (dependencyGraph?.hasInfo(uri) == true) {
            val boundedUris = dependencyGraph.getCompilationSources(uri)
            if (logBounded) {
                logger.debug { "Using bounded workspace sources for $uri: ${boundedUris.size} files" }
            }
            workspaceManager.getBoundedWorkspaceSources(boundedUris, workspace)
        } else {
            workspace.sources
        }
        // A buffer open under another spelling already carries its file: its listed disk copy would be a second
        // definition of every class in it (#1446).
        val hidden = shadowed()
        val scoped = policy?.sourcesFor(sources) ?: workspaceManager.sourcesFor(uri, workspace, sources)
        return if (hidden.isEmpty()) scoped else scoped.filterNot { it.toUri() in hidden }
    }

    /**
     * The other documents open in the editor, so a file compiles against their unsaved content:
     * two new files can reference each other before either is saved. The parser keeps only
     * `.groovy` sources and lets each buffer replace its on-disk copy.
     */
    private fun openSourcesFor(
        uri: URI,
        workspace: WorkspaceModel,
        policy: SourcePolicySnapshot?,
    ): Pair<Map<URI, String>, Map<URI, String>> {
        val scope = policy?.scope ?: workspaceManager.sourceScopeFor(uri, workspace)
        val buffers = documentProvider?.snapshot()?.filterKeys { it != uri }.orEmpty()
        if (scope.mode == SourceMode.LEGACY_UNKNOWN) {
            val filtered = buffers.filterKeys { candidate ->
                val path = runCatching { Path.of(candidate) }.getOrNull()
                path == null || scope.classify(path) != SourceCandidate.EXCLUDED
            }
            return filtered to emptyMap()
        }
        val eager = mutableMapOf<URI, String>()
        val onDemand = mutableMapOf<URI, String>()
        buffers.forEach { (candidate, content) ->
            val path = runCatching { Path.of(candidate) }.getOrNull() ?: return@forEach
            when (scope.classify(path)) {
                SourceCandidate.VISIBLE -> eager[candidate] = content
                SourceCandidate.UNKNOWN -> onDemand[candidate] = content
                SourceCandidate.EXCLUDED -> Unit
            }
        }
        return eager to onDemand
    }
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

private fun WorkerSessionManager.parseWithSourcePolicy(
    request: ParseRequest,
    sourceFilter: ((Path) -> Boolean)?,
    locatorBuffers: Map<URI, String>,
): ParseResult = if (sourceFilter == null && locatorBuffers.isEmpty()) {
    parse(request)
} else {
    parseWithSourceFilter(request, sourceFilter, locatorBuffers)
}

/** A hook that throws [CancellationException] once the calling coroutine is cancelled. */
private suspend fun cancellationCheck(): () -> Unit {
    val job = currentCoroutineContext()[Job]
    return { job?.ensureActive() }
}

/**
 * Says at INFO why a file compiled without its types. Once per (uri, outcome): typing in a degraded
 * file does not repeat it, a different cause logs again, and a clean compile resets it.
 */
private fun logIfDegraded(
    uri: URI,
    parseResult: ParseResult,
    loggedDegradations: ConcurrentHashMap<URI, ParseOutcome>,
    logger: KLogger,
) {
    val outcome = parseResult.outcome
    if (!outcome.isDegraded) {
        loggedDegradations.remove(uri)
        return
    }
    if (loggedDegradations.put(uri, outcome) != outcome) {
        logger.info { outcome.describe(uri.toString()) }
    }
}

private fun requireCurrentSourcePolicy(policy: SourcePolicySnapshot?, uri: URI) {
    if (policy?.isCurrent() == false) throw contentModified(uri)
}

private fun publishWithinSourcePolicy(policy: SourcePolicySnapshot?, commit: () -> Unit) {
    if (policy == null) commit() else policy.publishIfCurrent(commit)
}
