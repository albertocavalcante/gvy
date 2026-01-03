package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.engine.EngineFactory
import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.config.EngineConfiguration
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.services.GroovyGdkProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovylsp.version.GroovyVersionInfo
import com.github.albertocavalcante.groovylsp.worker.InProcessWorkerSession
import com.github.albertocavalcante.groovylsp.worker.WorkerDescriptor
import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.codehaus.groovy.control.Phases
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

private const val RETRY_DELAY_MS = 50L

/**
 * Service for compiling and managing Groovy source code.
 *
 * This service now acts as a facade that delegates to specialized services:
 * - CompilationCacheService: Caching and async job tracking
 * - SymbolIndexingService: Symbol indexing and workspace scanning
 * - ParseResultAccessor: AST and parse result access
 * - WorkspaceScanner: File discovery
 *
 * @param documentProvider Required for engine-based features (hover, completion, etc.).
 *                         If null, calling [getSession] will throw [IllegalStateException].
 * @param sourceNavigator Optional source navigation service for cross-file features.
 * @param engineConfig Configuration for the language engine (parser type, features).
 */
class GroovyCompilationService(
    parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val documentProvider: DocumentProvider? = null,
    private val sourceNavigator: SourceNavigator? = null,
    private var engineConfig: EngineConfiguration = EngineConfiguration(),
) {
    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val errorHandler = CompilationErrorHandler()
    private val parser = GroovyParserFacade(parentClassLoader)
    private val workerSessionManager = WorkerSessionManager(
        defaultSession = InProcessWorkerSession(parser),
        sessionFactory = { InProcessWorkerSession(parser) },
    )

    // Extracted services
    private val cacheService = CompilationCacheService(ioDispatcher)
    val workspaceManager = WorkspaceManager()
    private val symbolIndexer = SymbolIndexingService(
        ioDispatcher = ioDispatcher,
        workerSessionManager = workerSessionManager,
        workspaceManager = workspaceManager,
    )
    private val parseAccessor = ParseResultAccessor(
        cacheService = cacheService,
        workerSessionManager = workerSessionManager,
        workspaceManager = workspaceManager,
    )
    val workspaceScanner = WorkspaceScanner(ioDispatcher)

    private val groovyVersionInfo = AtomicReference<GroovyVersionInfo?>(null)
    private val selectedWorker = AtomicReference<WorkerDescriptor?>(null)

    private var activeEngineInstance: LanguageEngine? = null
    private val activeEngineLock = Any()

    // Services for GDK and classpath-based completion
    val classpathService = ClasspathService()
    val gdkProvider = GroovyGdkProvider(classpathService)

    // Language Engine created via factory based on configuration
    private val activeEngine: LanguageEngine
        get() = synchronized(activeEngineLock) {
            activeEngineInstance ?: createEngine().also { activeEngineInstance = it }
        }

    fun updateEngineConfiguration(newConfig: EngineConfiguration) {
        synchronized(activeEngineLock) {
            if (engineConfig != newConfig) {
                logger.info("Updating engine configuration from ${engineConfig.type.id} to ${newConfig.type.id}")
                engineConfig = newConfig
                activeEngineInstance = null // Invalidate to force re-creation
            }
        }
    }

    private fun createEngine(): LanguageEngine = EngineFactory.create(
        config = engineConfig,
        parser = parser,
        compilationService = this,
        documentProvider = checkNotNull(documentProvider) {
            "DocumentProvider required for engine features"
        },
        sourceNavigator = sourceNavigator,
    ).also { logger.info("Active engine initialized: ${engineConfig.type.id}") }

    /**
     * Compiles Groovy source code and returns the result.
     *
     * NOTE: The cache lookup uses (uri, content) as the key and does NOT consider
     * the [compilePhase] parameter. If a file was previously compiled to a later
     * phase, subsequent requests for earlier phases may return the cached result
     * from the later phase. This is a known limitation for Spock feature extraction,
     * which requires early-phase AST (before Spock's transformations).
     * TODO: Consider including compilePhase in the cache key for phase-sensitive use cases.
     */
    @Suppress("TooGenericExceptionCaught") // Final fallback
    suspend fun compile(
        uri: URI,
        content: String,
        compilePhase: Int = Phases.CANONICALIZATION,
    ): CompilationResult {
        logger.debug("Compiling: $uri (phase=$compilePhase)")

        return try {
            // Check cache first
            val cachedResult = cacheService.getCached(uri, content)
            if (cachedResult != null) {
                // Check for suspicious Script node
                val isSuspiciousScriptNode = parseAccessor.isSuspiciousScript(uri, cachedResult)

                if (isSuspiciousScriptNode) {
                    logger.info("Cached result has suspicious Script node for $uri, re-compiling")
                    performCompilation(uri, content, compilePhase)
                } else {
                    logger.debug("Using cached parse result for: $uri")
                    val ast = cachedResult.ast!!
                    val diagnostics = cachedResult.diagnostics.map { it.toLspDiagnostic() }
                    CompilationResult.success(ast, diagnostics, content)
                }
            } else {
                performCompilation(uri, content, compilePhase)
            }
        } catch (e: Exception) {
            errorHandler.handleException(e, uri)
        }
    }

    private suspend fun performCompilation(
        uri: URI,
        content: String,
        compilePhase: Int = Phases.CANONICALIZATION,
    ): CompilationResult {
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Get file-specific classpath
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Parse the source code
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
            ),
        )

        val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
        val ast = parseResult.ast

        val result = if (ast != null) {
            // Cache parse result
            cacheService.putCached(uri, content, parseResult)

            // Index symbols
            symbolIndexer.getSymbolIndex(uri) { parseResult.astModel }

            val isSuccess = parseResult.isSuccessful
            CompilationResult(isSuccess, ast, diagnostics, content)
        } else {
            CompilationResult.failure(diagnostics, content)
        }

        return result
    }

    /**
     * Compile a transient version without updating cache.
     * Used for completion with dummy identifiers.
     * Returns the full ParseResult for access to astModel, tokenIndex etc.
     */
    suspend fun compileTransient(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION): ParseResult {
        logger.debug("Transient compile: $uri")
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        return workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                compilePhase = compilePhase,
            ),
        )
    }

    /**
     * Start async compilation and return Deferred for coordination.
     * Reuses existing compilation if already in progress for the same URI.
     */
    fun compileAsync(scope: CoroutineScope, uri: URI, content: String): kotlinx.coroutines.Deferred<CompilationResult> {
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
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        // Check for active compilation first
        cacheService.getActiveCompilation(uri)?.let { deferred ->
            if (deferred.isActive) {
                logger.debug("Awaiting active compilation for: $uri")
                return try {
                    deferred.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // If compilation was cancelled, try once more
                    logger.debug("Compilation cancelled for $uri, retrying...")
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    cacheService.getActiveCompilation(uri)?.await()
                }
            }
        }

        // Check cache
        cacheService.getCached(uri)?.let { parseResult ->
            logger.debug("Using cached result for: $uri")
            val ast = parseResult.ast ?: return null
            val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
            return CompilationResult(parseResult.isSuccessful, ast, diagnostics, "")
        }

        logger.debug("No compilation found for $uri (not cached, not compiling)")
        return null
    }

    /**
     * Gets the language session for the given URI.
     * Delegates to the active language engine to wrap the parse result.
     */
    fun getSession(uri: URI): LanguageSession? {
        val cached = cacheService.getCachedWithContent(uri) ?: return null
        val (content, _) = cached
        return activeEngine.createSession(uri, content)
    }

    fun clearCaches() {
        cacheService.clear()
        symbolIndexer.clear()
        invalidateClassLoader()
    }

    fun updateGroovyVersion(info: GroovyVersionInfo) {
        groovyVersionInfo.set(info)
        logger.info("Groovy version resolved: {} (source={})", info.version.raw, info.source)
    }

    fun getGroovyVersionInfo(): GroovyVersionInfo? = groovyVersionInfo.get()

    fun updateSelectedWorker(worker: WorkerDescriptor?): Boolean {
        val previous = selectedWorker.getAndSet(worker)
        val changed = previous != worker
        if (changed) {
            workerSessionManager.select(worker)
            clearCaches()
            logger.info("Worker changed; cleared compilation caches")
        }
        if (worker != null) {
            logger.info(
                "Worker selected: {} (range={}, features={})",
                worker.id,
                worker.supportedRange,
                worker.capabilities.features,
            )
        } else {
            logger.warn("No compatible worker selected")
        }
        return changed
    }

    fun getSelectedWorker(): WorkerDescriptor? = selectedWorker.get()

    /**
     * Invalidates all cached data for a specific URI.
     */
    fun invalidateCache(uri: URI) {
        cacheService.invalidate(uri)
        symbolIndexer.invalidate(uri)
        logger.debug("Invalidated cache for: $uri")
    }

    fun getCacheStatistics() = cacheService.getStatistics()

    /**
     * Gets global variables defined in Jenkins workspace.
     */
    fun getJenkinsGlobalVariables() = workspaceManager.getJenkinsGlobalVariables()

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>) {
        val changed = workspaceManager.updateWorkspaceModel(workspaceRoot, dependencies, sourceDirectories)
        if (changed) {
            classpathService.updateClasspath(dependencies)
            gdkProvider.initialize()
            clearCaches()
        }
    }

    fun createContext(uri: URI): CompilationContext? {
        val parseResult = parseAccessor.getParseResult(uri) ?: return null
        val ast = parseResult.ast ?: return null

        return CompilationContext(
            uri = uri,
            moduleNode = ast,
            astModel = parseResult.astModel,
            workspaceRoot = workspaceManager.getWorkspaceRoot(),
            classpath = workspaceManager.getDependencyClasspath(),
        )
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

    // Expose cache for testing purposes
    internal val astCache get() = cacheService

    private val classLoaderLock = Any()
    private var cachedClassLoader: URLClassLoader? = null

    /**
     * Find a class on the dependency classpath and return its URI.
     */
    fun findClasspathClass(className: String): URI? {
        val classLoader = getOrCreateClassLoader()
        val resourceName = className.replace('.', '/') + ".class"
        val resource = classLoader.getResource(resourceName) ?: return null
        return resource.toURI()
    }

    private fun getOrCreateClassLoader(): URLClassLoader = synchronized(classLoaderLock) {
        cachedClassLoader ?: run {
            val classpath = workspaceManager.getDependencyClasspath()
            val urls = classpath.map { it.toUri().toURL() }.toTypedArray()
            URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).also {
                cachedClassLoader = it
            }
        }
    }

    private fun invalidateClassLoader() {
        synchronized(classLoaderLock) {
            cachedClassLoader?.close()
            cachedClassLoader = null
        }
    }

    // ==========================================================================
    // Delegation Methods for Backward Compatibility
    // These methods delegate to extracted services for consumers that haven't
    // been updated yet. New code should use the exposed services directly.
    // ==========================================================================

    /** Delegates to [ParseResultAccessor.getAst] */
    fun getAst(uri: URI) = parseAccessor.getAst(uri)

    /** Delegates to [ParseResultAccessor.getParseResult] */
    fun getParseResult(uri: URI) = parseAccessor.getParseResult(uri)

    /** Delegates to [ParseResultAccessor.getDiagnostics] */
    fun getDiagnostics(uri: URI) = parseAccessor.getDiagnostics(uri)

    /** Delegates to [ParseResultAccessor.getTokenIndex] */
    fun getTokenIndex(uri: URI) = parseAccessor.getTokenIndex(uri)

    /** Delegates to [ParseResultAccessor.getAstModel] */
    fun getAstModel(uri: URI) = parseAccessor.getAstModel(uri)

    /** Delegates to [ParseResultAccessor.getSymbolTable] */
    fun getSymbolTable(uri: URI) = parseAccessor.getSymbolTable(uri)

    /** Delegates to [ParseResultAccessor.getValidParseResult] */
    suspend fun getValidParseResult(uri: URI) = parseAccessor.getValidParseResult(uri)

    /** Delegates to [SymbolIndexingService.getSymbolIndex] */
    fun getSymbolStorage(uri: URI) = symbolIndexer.getSymbolIndex(uri)

    /** Delegates to [SymbolIndexingService.getAllSymbolIndices] */
    fun getAllSymbolStorages() = symbolIndexer.getAllSymbolIndices()

    /** Delegates to [SymbolIndexingService.indexAllWorkspaceSources] */
    suspend fun indexAllWorkspaceSources(uris: List<URI>, onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        symbolIndexer.indexAllWorkspaceSources(uris, onProgress)

    // ==========================================================================
    // Expose services for external access
    // ==========================================================================
    val compilationCacheService: CompilationCacheService get() = cacheService
    val symbolIndexingService: SymbolIndexingService get() = symbolIndexer
    val parseResultAccessor: ParseResultAccessor get() = parseAccessor
}
