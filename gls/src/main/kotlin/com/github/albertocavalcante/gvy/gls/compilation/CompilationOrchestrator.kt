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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
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

    private val logger = KotlinLogging.logger {}

    /** The content hash whose degradation was last logged, per file. */
    private val loggedDegradations = ConcurrentHashMap<URI, ParseOutcome>()

    private data class CompilationInput(
        val uri: URI,
        val content: String,
        val publication: SemanticIndexer.Publication?,
    )

    /**
     * Compiles Groovy source code and returns the result.
     */
    suspend fun compile(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): CompilationResult =
        semanticIndexer?.begin(uri).use { compile(uri, content, compilePhase, it) }

    private suspend fun compile(
        uri: URI,
        content: String,
        compilePhase: Int,
        publication: SemanticIndexer.Publication?,
    ): CompilationResult {
        logger.debug { "Compiling: $uri (phase=$compilePhase)" }

        return runCatching {
            // TODO(#1184): Capture queued compiler inputs together with their request identity.
            //   See: https://github.com/albertocavalcante/gvy/issues/1184
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
                        CompilationInput(uri, content, publication),
                        compilePhase,
                        configFingerprint = configFingerprint,
                    )
                } else {
                    logger.debug { "Using cached parse result for: $uri" }
                    // The symbol index may be absent or older than this result (e.g. after invalidation).
                    symbolIndexer.ensureIndexed(uri, content) { cachedResult.astModel }
                    cachedResult.ast?.let {
                        publication?.let { token ->
                            semanticIndexer?.publish(token, content, it, cachedResult.outcome.achievedPhase)
                        }
                    }
                    resultMapper.map(cachedResult, content)
                }
            } else {
                performCompilation(
                    CompilationInput(uri, content, publication),
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
        val (uri, content, publication) = input
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
            // Cache parse result with configuration fingerprint for coherency (Issue #743)
            val fingerprint = configFingerprint ?: workspaceManager.getConfigurationFingerprint()
            cacheService.putCached(uri, content, parseResult, fingerprint)

            // Index symbols
            symbolIndexer.indexCompiled(uri, content, parseResult.astModel)

            // Semantic document for cross-file resolution
            publication?.let { semanticIndexer?.publish(it, content, ast, parseResult.outcome.achievedPhase) }
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
        val publication = semanticIndexer?.begin(uri)
        var created = false
        var retainedPublication = false
        try {
            val deferred = cacheService.registerCompilation(uri, identity) {
                scope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                    compile(uri, identity.content, Phases.CANONICALIZATION, publication)
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

    private suspend fun awaitCompilation(uri: URI, identity: CompilationIdentity): CompilationResult {
        val scope = CoroutineScope(currentCoroutineContext())
        return try {
            scheduleCompilation(scope, uri, identity).await()
        } catch (e: CancellationException) {
            // A separate owner may have cancelled shared work; a cancelled waiter must never retry.
            currentCoroutineContext().ensureActive()
            logger.debug(e) { "Compilation owner cancelled for $uri, retrying with current caller" }
            val retry = CompilationIdentity(
                identity.content,
                workspaceManager.getConfigurationFingerprint(),
                identity.document,
            )
            scheduleCompilation(scope, uri, retry).await()
        }
    }

    /**
     * Ensures a file is compiled, either by awaiting active compilation or fetching from cache.
     * Validates configuration fingerprint for cache coherency (Issue #743).
     */
    @Suppress("ReturnCount") // Multiple cache and validation checks require early returns
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        // An open buffer is the source of truth: it may be unsaved, untitled, or newer than the file on
        // disk or the cached parse, and didOpen compiles it in a background job that may not have
        // started yet. Resolve its identity before selecting any pending job.
        documentProvider?.getSnapshot(uri)?.let { snapshot ->
            val identity =
                CompilationIdentity(snapshot.content, workspaceManager.getConfigurationFingerprint(), snapshot)
            return awaitCompilation(uri, identity)
        }

        // Get current fingerprint for cache coherency validation
        val configFingerprint = workspaceManager.getConfigurationFingerprint()

        cacheService.getPendingCompilation(uri, configFingerprint)?.let { pending ->
            return awaitCompilation(uri, pending.identity)
        }

        // Check cache with fingerprint validation
        cacheService.getCachedWithContent(uri)?.let { (content, parseResult) ->
            // Validate by attempting to get with fingerprint - if it returns null, cache is stale
            val validatedResult = cacheService.getCached(uri, content, configFingerprint)
            if (validatedResult != null) {
                logger.debug { "Using cached result for: $uri" }
                return resultMapper.mapFromCache(parseResult, content)
            }
            logger.debug { "Cache entry for $uri is stale (fingerprint mismatch), will recompile" }
        }

        // Use on-demand compilation from disk if file exists
        // This handles cases where file is in symbol index but not yet opened/compiled
        val path = runCatching { Path.of(uri) }.getOrNull()
        if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
            logger.debug { "Compiling from disk on-demand: $uri" }
            return semanticIndexer?.begin(uri).use { publication ->
                try {
                    val content = withContext(ioDispatcher) {
                        Files.readString(path)
                    }
                    // Use MINIMAL mode for on-demand navigation requests to avoid workspace-wide recompiles
                    performCompilation(
                        CompilationInput(uri, content, publication),
                        parseMode = ParseMode.MINIMAL,
                        configFingerprint = configFingerprint,
                    )
                } catch (e: CancellationException) {
                    throw e // the request was cancelled; not a failure to compile
                } catch (e: Exception) {
                    logger.error(e) { "Failed to compile from disk for $uri: ${e.message}" }
                    null
                }
            }
        }

        logger.debug { "No compilation found for $uri (not cached, not compiling, not on disk)" }
        return null
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
}
