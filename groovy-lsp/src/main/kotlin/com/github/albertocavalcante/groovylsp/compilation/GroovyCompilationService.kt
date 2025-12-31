package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.engine.EngineFactory
import com.github.albertocavalcante.groovylsp.engine.api.LanguageEngine
import com.github.albertocavalcante.groovylsp.engine.api.LanguageSession
import com.github.albertocavalcante.groovylsp.engine.config.EngineConfiguration
import com.github.albertocavalcante.groovylsp.engine.impl.native.NativeLanguageEngine
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
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val RETRY_DELAY_MS = 50L

class GroovyCompilationService(
    private val parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val documentProvider: DocumentProvider? = null,
    private val sourceNavigator: SourceNavigator? = null,
    private val engineConfig: EngineConfiguration = EngineConfiguration(),
) {
    companion object {
        /**
         * Batch size for parallel workspace indexing.
         * Balances parallelism with resource usage.
         */
        private const val INDEXING_BATCH_SIZE = 10
    }

    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val cache = CompilationCache()
    private val errorHandler = CompilationErrorHandler()
    private val parser = GroovyParserFacade(parentClassLoader)
    private val workerSessionManager = WorkerSessionManager(
        defaultSession = InProcessWorkerSession(parser),
        sessionFactory = { InProcessWorkerSession(parser) },
    )
    private val symbolStorageCache = LRUCache<URI, SymbolIndex>(maxSize = 100)
    private val groovyVersionInfo = AtomicReference<GroovyVersionInfo?>(null)
    private val selectedWorker = AtomicReference<WorkerDescriptor?>(null)

    // Language Engine created via factory based on configuration
    private val activeEngine: LanguageEngine by lazy {
        EngineFactory.create(
            config = engineConfig,
            parser = parser,
            compilationService = this,
            documentProvider = documentProvider
                ?: throw IllegalStateException("DocumentProvider required for engine features"),
            sourceNavigator = sourceNavigator,
        ).also { logger.info("Active engine: ${engineConfig.type.id}") }
    }

    // Callback to wait for initialization (e.g. dependency resolution)
    var initializationBarrier: (suspend () -> Boolean)? = null

    // Track ongoing compilation per URI for proper async coordination
    private val compilationJobs = ConcurrentHashMap<URI, Deferred<CompilationResult>>()

    val workspaceManager = WorkspaceManager()

    // Services for GDK and classpath-based completion
    val classpathService = ClasspathService()
    val gdkProvider = GroovyGdkProvider(classpathService)

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
            val cachedResult = cache.get(uri, content)
            if (cachedResult != null) {
                // Check for suspicious Script node - this happens when file was compiled
                // before sourceRoots was populated (e.g., during didOpen before workspace init)
                val isSuspiciousScriptNode = isSuspiciousScript(uri, cachedResult)

                if (isSuspiciousScriptNode) {
                    // Cache has a Script node that might be stale - re-compile with current sourceRoots
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
        // Wait for initialization (e.g. dependencies) before starting expensive/fragile compilation
        initializationBarrier?.let { barrier ->
            if (!barrier()) {
                logger.warn("Initialization barrier timed out/failed, proceeding with compilation anyway for $uri")
            }
        }

        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Get file-specific classpath (may be Jenkins-specific or standard)
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        // Parse the source code
        // Note: GroovyParserFacade automatically retries at CONVERSION phase if it detects
        // Script fallback (when a class extends groovy.lang.Script due to unresolved superclass)
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
            cache.put(uri, content, parseResult)
            symbolStorageCache.put(uri, SymbolIndex().buildFromVisitor(parseResult.astModel))
            val isSuccess = parseResult.isSuccessful
            CompilationResult(isSuccess, ast, diagnostics, content)
        } else {
            symbolStorageCache.remove(uri)
            CompilationResult.failure(diagnostics, content)
        }

        logger.debug("Compilation result for $uri: success=${result.isSuccess}, diagnostics=${diagnostics.size}")
        return result
    }

    /**
     * Compiles code without updating the cache.
     * Useful for completion where we insert a dummy identifier.
     */
    suspend fun compileTransient(
        uri: URI,
        content: String,
        compilePhase: Int = Phases.CANONICALIZATION,
    ): com.github.albertocavalcante.groovyparser.api.ParseResult {
        // Wait for initialization (e.g. dependencies)
        initializationBarrier?.let { barrier ->
            if (!barrier()) {
                logger.warn("Initialization barrier timed out/failed during transient compilation for $uri")
            }
        }
        logger.debug("Transient compilation for: $uri (phase=$compilePhase)")
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

    fun getParseResult(uri: URI): com.github.albertocavalcante.groovyparser.api.ParseResult? = cache.get(uri)

    /**
     * Checks if a parse result contains a suspicious Script node.
     *
     * A "suspicious" Script node is one where:
     * - There's exactly one class in the AST
     * - The class extends groovy.lang.Script
     * - The class name matches the filename
     *
     * This typically happens when a file is compiled before sourceRoots were populated,
     * causing the Groovy compiler to fall back to treating it as a Script.
     */
    private fun isSuspiciousScript(
        uri: URI,
        parseResult: com.github.albertocavalcante.groovyparser.api.ParseResult,
    ): Boolean {
        val filename = runCatching { Path.of(uri).fileName.toString().substringBeforeLast(".") }.getOrNull()
            ?: return false
        val classes = parseResult.ast?.classes ?: return false

        if (classes.size != 1) {
            return false
        }
        val singleClass = classes.single()
        // Use safe call for superClass - it's null for interfaces
        return singleClass.superClass?.name == "groovy.lang.Script" && singleClass.name == filename
    }

    /**
     * Gets a valid parse result for the URI, ensuring stale Script nodes are recompiled.
     *
     * Unlike [getParseResult], this method checks if the cached result contains a suspicious
     * Script node (which can happen when file was compiled before sourceRoots was populated).
     * If detected, it parses directly at CONVERSION phase WITHOUT workspaceSources to avoid
     * name collisions that cause Script fallback.
     *
     * Use this method when you need to ensure the AST accurately represents the source code
     * structure (e.g., test discovery, Spock detection).
     */
    suspend fun getValidParseResult(uri: URI): com.github.albertocavalcante.groovyparser.api.ParseResult? {
        val cachedResult = cache.get(uri) ?: return null

        // Use helper function to check for suspicious Script node
        if (isSuspiciousScript(uri, cachedResult)) {
            logger.info("Cached result has suspicious Script node for $uri, parsing directly at CONVERSION phase")
            // Read content directly from file instead of cache to verify content is correct
            val sourcePath = runCatching { Path.of(uri) }.getOrNull()
            val fileContent = if (sourcePath != null && Files.exists(sourcePath)) {
                Files.readString(sourcePath)
            } else {
                cache.getWithContent(uri)?.first
            }
            val content = fileContent ?: return null
            logger.info("Content starts with: '${content.take(50).replace("\n", "\\n")}'")
            val parseResult = workerSessionManager.parse(
                ParseRequest(
                    uri = uri,
                    content = content,
                    classpath = workspaceManager.getClasspathForFile(uri, content),
                    // Don't add source roots - prevents classloader from finding .groovy file
                    sourceRoots = emptyList(),
                    // Don't add other sources - causes Script fallback
                    workspaceSources = emptyList(),
                    locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                    compilePhase = Phases.CONVERSION,
                ),
            )

            // Update cache with correct result
            cache.put(uri, content, parseResult)
            logger.info(
                "Re-parsed $uri at CONVERSION: classes=${
                    parseResult.ast?.classes?.map {
                        "${it.name} (super=${it.superClass?.name ?: "null"})"
                    }
                }",
            )
            logger.debug("Content preview: ${content.take(100).replace("\n", "\\n")}")
            return parseResult
        }

        return cachedResult
    }

    fun getAst(uri: URI): ASTNode? = getParseResult(uri)?.ast

    fun getDiagnostics(uri: URI): List<Diagnostic> =
        getParseResult(uri)?.diagnostics?.map { it.toLspDiagnostic() } ?: emptyList()

    fun getAstModel(uri: URI): GroovyAstModel? = getParseResult(uri)?.astModel

    fun getSymbolTable(uri: URI): SymbolTable? = getParseResult(uri)?.symbolTable

    fun getTokenIndex(uri: URI) = getParseResult(uri)?.tokenIndex

    fun getSymbolStorage(uri: URI): SymbolIndex? {
        symbolStorageCache.get(uri)?.let { return it }
        val visitor = getAstModel(uri) ?: return null
        val storage = SymbolIndex().buildFromVisitor(visitor)
        symbolStorageCache.put(uri, storage)
        return storage
    }

    /**
     * Gets the language session for the given URI.
     * Delegates to the active language engine to wrap the parse result.
     * NOTE: This assumes the file is already compiled/parsed (returns null if not in cache).
     */
    fun getSession(uri: URI): com.github.albertocavalcante.groovylsp.engine.api.LanguageSession? {
        val parseResult = getParseResult(uri)
        if (parseResult == null) {
            logger.debug("getSession({}): parseResult is null, file not in cache", uri)
            return null
        }

        logger.debug("getSession({}): parseResult found, checking activeEngine", uri)

        // Bridge: Delegate to NativeEngine to wrap the result
        // In future phases, we might need a cache keyed by Engine ID or unified result
        if (activeEngine is NativeLanguageEngine) {
            logger.debug("getSession({}): activeEngine is NativeLanguageEngine, creating session", uri)
            return (activeEngine as NativeLanguageEngine).createSession(
                parseResult,
            )
        }

        logger.debug(
            "getSession({}): activeEngine is NOT NativeLanguageEngine ({})",
            uri,
            activeEngine::class.simpleName,
        )
        // Fallback for other engines (or if cache format mismatch)
        return null
    }

    fun getAllSymbolStorages(): Map<URI, SymbolIndex> {
        val allStorages = mutableMapOf<URI, SymbolIndex>()
        cache.keys().forEach { uri ->
            getSymbolStorage(uri)?.let { allStorages[uri] = it }
        }
        return allStorages
    }

    /**
     * Indexes a single workspace file for symbol resolution.
     * Lightweight operation that parses and builds SymbolIndex without full compilation.
     *
     * @param uri The URI of the file to index
     * @return SymbolIndex if indexing succeeded, null otherwise
     */
    suspend fun indexWorkspaceFile(uri: URI): SymbolIndex? {
        // Check if already indexed first
        symbolStorageCache.get(uri)?.let {
            logger.debug("File already indexed: $uri")
            return it
        }

        val path = parseUriToPath(uri) ?: return null

        return when {
            !Files.exists(path) || !Files.isRegularFile(path) -> {
                logger.debug("File does not exist or is not a regular file: $uri")
                null
            }

            else -> performIndexing(uri, path)
        }
    }

    private fun parseUriToPath(uri: URI): Path? = try {
        Path.of(uri)
    } catch (e: java.nio.file.InvalidPathException) {
        logger.debug("Failed to convert URI to path: $uri", e)
        null
    }

    // NOTE: Various exceptions possible (IOException, ParseException, etc.)
    // Catch all to prevent indexing failure from stopping workspace indexing
    @Suppress("TooGenericExceptionCaught")
    private suspend fun performIndexing(uri: URI, path: Path): SymbolIndex? = try {
        val content = Files.readString(path)
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()

        // Parse the source code for indexing
        // Note: GroovyParserFacade automatically retries at CONVERSION phase if it detects
        // Script fallback (when a class extends groovy.lang.Script due to unresolved superclass)
        val parseResult = workerSessionManager.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = workspaceManager.getDependencyClasspath(),
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = emptyList(), // Don't recurse during indexing
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
            ),
        )

        val astModel = parseResult.astModel
        val index = SymbolIndex().buildFromVisitor(astModel)
        symbolStorageCache.put(uri, index)
        logger.debug("Indexed workspace file: $uri")
        index
    } catch (e: Exception) {
        logger.warn("Failed to index workspace file: $uri", e)
        null
    }

    /**
     * Indexes all workspace source files in the background.
     * Reports progress via callback function.
     *
     * @param uris List of URIs to index
     * @param onProgress Callback invoked with (indexed, total) progress
     */
    suspend fun indexAllWorkspaceSources(uris: List<URI>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        if (uris.isEmpty()) {
            logger.debug("No workspace sources to index")
            return
        }

        logger.info("Starting workspace indexing: ${uris.size} files")
        val total = uris.size
        val indexed = AtomicInteger(0)

        // Index files in parallel batches
        // NOTE: Batch size balances parallelism with resource usage
        // Uses ioDispatcher since call chain includes blocking I/O (Files.readString)
        uris.chunked(INDEXING_BATCH_SIZE).forEach { batch ->
            coroutineScope {
                batch.forEach { uri ->
                    @Suppress("kotlin:S6311") // NOSONAR - IO dispatcher required for blocking file operations
                    launch(ioDispatcher) {
                        indexFileWithProgress(uri, indexed, total, onProgress)
                    }
                }
            }
        }

        logger.info("Workspace indexing complete: ${indexed.get()}/$total files indexed")
    }

    /**
     * Indexes a single file and reports progress atomically.
     */
    @Suppress("TooGenericExceptionCaught") // NOTE: Various exceptions possible (IOException, ParseException, etc.)
    private suspend fun indexFileWithProgress(
        uri: URI,
        indexed: AtomicInteger,
        total: Int,
        onProgress: (Int, Int) -> Unit,
    ) {
        try {
            indexWorkspaceFile(uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            // Catch all to prevent batch failure from stopping entire indexing
            logger.warn("Failed to index file: $uri", e)
        } finally {
            val currentCount = indexed.incrementAndGet()
            onProgress(currentCount, total)
        }
    }

    /**
     * Start async compilation and return Deferred for coordination.
     * Reuses existing compilation if already in progress for the same URI.
     */
    fun compileAsync(scope: CoroutineScope, uri: URI, content: String): Deferred<CompilationResult> {
        // Check if already compiling this document
        compilationJobs[uri]?.let { existing ->
            if (existing.isActive) {
                logger.debug("Reusing active compilation for $uri")
                return existing
            }
        }

        // Start new compilation on IO dispatcher for file operations
        @Suppress("kotlin:S6311") // NOSONAR - IO dispatcher required for blocking file operations
        val deferred = scope.async(ioDispatcher) {
            try {
                compile(uri, content)
            } finally {
                compilationJobs.remove(uri)
            }
        }

        compilationJobs[uri] = deferred
        return deferred
    }

    /**
     * Ensure document is compiled, awaiting if compilation is in progress.
     * Returns immediately if document is already cached.
     * Returns null if document is not cached and not currently compiling.
     */

    /**
     * Ensure document is compiled, awaiting if compilation is in progress.
     * Returns immediately if document is already cached.
     * Returns null if document is not cached and not currently compiling.
     */
    suspend fun ensureCompiled(uri: URI): CompilationResult? {
        while (true) {
            // If currently compiling, await it
            val deferred = compilationJobs[uri]
            if (deferred != null) {
                try {
                    logger.debug("Awaiting active compilation for $uri")
                    return deferred.await()
                } catch (e: CancellationException) {
                    logger.debug("Compilation cancelled for $uri while awaiting - retrying ensureCompiled")
                    // Give a small grace period for the new compilation to start if this was a restart
                    kotlinx.coroutines.delay(RETRY_DELAY_MS)
                    continue
                }
            }

            // Check if already in cache
            cache.getWithContent(uri)?.let { (cachedContent, parseResult) ->
                logger.debug("Using cached compilation for $uri")
                val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
                val ast = parseResult.ast
                val sourceText = cachedContent
                return if (ast != null) {
                    CompilationResult.success(ast, diagnostics, sourceText)
                } else {
                    CompilationResult.failure(diagnostics, sourceText)
                }
            }

            // Not compiling and not cached
            logger.debug("No compilation found for $uri (not cached, not compiling)")
            return null
        }
    }

    fun clearCaches() {
        cache.clear()
        symbolStorageCache.clear()
        compilationJobs.clear()
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
     * Used when a file is deleted or needs to be fully re-indexed.
     */
    fun invalidateCache(uri: URI) {
        cache.invalidate(uri)
        symbolStorageCache.remove(uri)
        compilationJobs.remove(uri)
        logger.debug("Invalidated cache for: $uri")
    }

    fun getCacheStatistics() = cache.getStatistics()

    /**
     * Gets global variables defined in Jenkins workspace (e.g. vars/ directory).
     * Used by DefinitionResolver to resolve go-to-definition for Jenkins vars calls.
     */
    fun getJenkinsGlobalVariables() = workspaceManager.getJenkinsGlobalVariables()

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>) {
        val changed = workspaceManager.updateWorkspaceModel(workspaceRoot, dependencies, sourceDirectories)
        if (changed) {
            logger.info("Workspace model changed, updating classpath services")
            // Update classpath service with new dependencies
            classpathService.updateClasspath(dependencies)
            // Initialize GDK provider (indexes GDK classes)
            gdkProvider.initialize()
            clearCaches()
        }
    }

    fun createContext(uri: URI): CompilationContext? {
        val parseResult = getParseResult(uri) ?: return null
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
    internal val astCache get() = cache

    private val classLoaderLock = Any()
    private var cachedClassLoader: java.net.URLClassLoader? = null

    /**
     * Find a class on the dependency classpath and return its URI.
     * Returns a 'jar:file:...' URI if found in a JAR, or 'file:...' if in a directory.
     */
    fun findClasspathClass(className: String): URI? {
        val loader = getOrCreateClassLoader()
        val resourcePath = className.replace('.', '/') + ".class"
        val resource = loader.getResource(resourcePath) ?: return null

        return try {
            resource.toURI()
        } catch (e: Exception) {
            logger.warn("Failed to convert resource URL to URI: $resource", e)
            null
        }
    }

    private fun getOrCreateClassLoader(): java.net.URLClassLoader {
        synchronized(classLoaderLock) {
            cachedClassLoader?.let { return it }

            val classpath = workspaceManager.getDependencyClasspath()
            val urls = classpath.map { it.toUri().toURL() }.toTypedArray()
            val loader = java.net.URLClassLoader(urls, null) // Parent null to only search dependencies
            cachedClassLoader = loader
            return loader
        }
    }

    private fun invalidateClassLoader() {
        synchronized(classLoaderLock) {
            try {
                cachedClassLoader?.close()
            } catch (e: Exception) {
                logger.warn("Error closing class loader", e)
            }
            cachedClassLoader = null
        }
    }
}
