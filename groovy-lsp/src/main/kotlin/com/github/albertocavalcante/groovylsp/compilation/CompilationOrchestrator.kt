package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.codehaus.groovy.control.Phases
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private const val RETRY_DELAY_MS = 50L

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
    val semanticDb: GroovySemanticDB? = null,
    val dependencyGraph: DependencyGraph? = null,
)

class CompilationOrchestrator(dependencies: CompilationOrchestratorDependencies) {
    private val cacheService = dependencies.cacheService
    private val workerSessionManager = dependencies.workerSessionManager
    private val workspaceManager = dependencies.workspaceManager
    private val symbolIndexer = dependencies.symbolIndexer
    private val parseAccessor = dependencies.parseAccessor
    private val resultMapper = dependencies.resultMapper
    private val ioDispatcher = dependencies.ioDispatcher
    private val errorHandler = dependencies.errorHandler
    private val semanticDb = dependencies.semanticDb
    private val dependencyGraph = dependencies.dependencyGraph

    private val logger = LoggerFactory.getLogger(CompilationOrchestrator::class.java)

    /**
     * Compiles Groovy source code and returns the result.
     */
    suspend fun compile(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): CompilationResult {
        logger.debug("Compiling: $uri (phase=$compilePhase)")

        return runCatching {
            // Get configuration fingerprint for cache coherency (Issue #743)
            val configFingerprint = workspaceManager.getConfigurationFingerprint()

            // Check cache first - validates both content AND configuration fingerprint
            val cachedResult = cacheService.getCached(uri, content, configFingerprint)
            if (cachedResult != null) {
                // Check for suspicious Script node
                val isSuspiciousScriptNode = parseAccessor.isSuspiciousScript(uri, cachedResult)

                if (isSuspiciousScriptNode) {
                    logger.info("Cached result has suspicious Script node for $uri, re-compiling")
                    performCompilation(uri, content, compilePhase, configFingerprint = configFingerprint)
                } else {
                    logger.debug("Using cached parse result for: $uri")
                    resultMapper.map(cachedResult, content)
                }
            } else {
                performCompilation(uri, content, compilePhase, configFingerprint = configFingerprint)
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
        uri: URI,
        content: String,
        compilePhase: Int = Phases.CANONICALIZATION,
        parseMode: ParseMode = ParseMode.WORKSPACE,
        configFingerprint: String? = null,
    ): CompilationResult {
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Get file-specific classpath
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Use bounded workspace selection when dependency info is available (Issue #743)
        // This provides O(k) compilation instead of O(n) where k = direct deps + dependents
        val workspaceSources = getWorkspaceSourcesForCompilation(uri, logBounded = true)

        // Parse the source code
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceSources,
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
                parseMode = parseMode,
            ),
        )

        val ast = parseResult.ast

        if (ast != null) {
            // Cache parse result with configuration fingerprint for coherency (Issue #743)
            val fingerprint = configFingerprint ?: workspaceManager.getConfigurationFingerprint()
            cacheService.putCached(uri, content, parseResult, fingerprint)

            // Index symbols
            symbolIndexer.getSymbolIndex(uri) { parseResult.astModel }

            // Build semantic document for cross-file resolution
            buildSemanticDocument(uri, ast)
        }

        return resultMapper.map(parseResult, content)
    }

    /**
     * Compile a transient version without updating cache.
     */
    suspend fun compileTransient(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): ParseResult {
        logger.debug("Transient compile: $uri")
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Use bounded workspace selection when available (Issue #743)
        val workspaceSources = getWorkspaceSourcesForCompilation(uri)

        return workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceSources,
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
            ),
        )
    }

    /**
     * Start async compilation and return Deferred for coordination.
     */
    fun compileAsync(scope: CoroutineScope, uri: URI, content: String): Deferred<CompilationResult> {
        // Check if already compiling this document
        cacheService.getActiveCompilation(uri)?.let { existing ->
            if (existing.isActive) {
                logger.debug("Reusing active compilation for: $uri")
                return existing
            }
        }

        val deferred = scope.async(ioDispatcher) {
            try {
                compile(uri, content)
            } finally {
                cacheService.removeCompilation(uri)
            }
        }

        cacheService.trackCompilation(uri, deferred)
        return deferred
    }

    /**
     * Ensures a file is compiled, either by awaiting active compilation or fetching from cache.
     * Validates configuration fingerprint for cache coherency (Issue #743).
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        // Check for active compilation first
        cacheService.getActiveCompilation(uri)?.let { deferred ->
            if (deferred.isActive) {
                logger.debug("Awaiting active compilation for: $uri")
                return try {
                    deferred.await()
                } catch (e: CancellationException) {
                    // If compilation was cancelled, try once more
                    logger.debug("Compilation cancelled for $uri, retrying...", e)
                    delay(RETRY_DELAY_MS)
                    cacheService.getActiveCompilation(uri)?.await()
                }
            }
        }

        // Get current fingerprint for cache coherency validation
        val configFingerprint = workspaceManager.getConfigurationFingerprint()

        // Check cache with fingerprint validation
        cacheService.getCachedWithContent(uri)?.let { (content, parseResult) ->
            // Validate by attempting to get with fingerprint - if it returns null, cache is stale
            val validatedResult = cacheService.getCached(uri, content, configFingerprint)
            if (validatedResult != null) {
                logger.debug("Using cached result for: $uri")
                return resultMapper.mapFromCache(parseResult, content)
            }
            logger.debug("Cache entry for $uri is stale (fingerprint mismatch), will recompile")
        }

        // Use on-demand compilation from disk if file exists
        // This handles cases where file is in symbol index but not yet opened/compiled
        val path = runCatching { Path.of(uri) }.getOrNull()
        if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
            logger.debug("Compiling from disk on-demand: $uri")
            return try {
                val content = withContext(ioDispatcher) {
                    Files.readString(path)
                }
                // Use MINIMAL mode for on-demand navigation requests to avoid workspace-wide recompiles
                performCompilation(uri, content, parseMode = ParseMode.MINIMAL, configFingerprint = configFingerprint)
            } catch (e: Exception) {
                logger.error("Failed to compile from disk for $uri: ${e.message}", e)
                null
            }
        }

        logger.debug("No compilation found for $uri (not cached, not compiling, not on disk)")
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
                logger.debug("Using bounded workspace sources for {}: {} files", uri, boundedUris.size)
            }
            workspaceManager.getBoundedWorkspaceSources(boundedUris)
        } else {
            workspaceManager.getWorkspaceSources()
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

    /**
     * Builds a semantic document from the AST and updates the SemanticDB.
     * This enables cross-file symbol resolution via SemanticDBResolutionStrategy.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun buildSemanticDocument(uri: URI, ast: org.codehaus.groovy.ast.ModuleNode) {
        val db = semanticDb ?: return
        try {
            val builder = SemanticDocumentBuilder(ast, uri)
            val semanticDoc = builder.build()
            db.updateDocument(uri, semanticDoc)
            logger.trace("Built semantic document for {} with {} symbols", uri, semanticDoc.symbols.size)
        } catch (e: Exception) {
            logger.warn("Failed to build semantic document for {}: {}", uri, e.message)
        }
    }
}
