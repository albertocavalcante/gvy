package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.cache.LRUCache
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.GroovyGdkProvider
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import kotlinx.coroutines.CancellationException
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

class GroovyCompilationService {
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
    private val parser = GroovyParserFacade()
    private val symbolStorageCache = LRUCache<URI, SymbolIndex>(maxSize = 100)

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
                logger.debug("Using cached parse result for: $uri")
                val ast = cachedResult.ast!!
                // Convert compilation diagnostics (errors)
                val diagnostics = cachedResult.diagnostics.map { it.toLspDiagnostic() }
                return CompilationResult.success(ast, diagnostics, content)
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

        // Get file-specific classpath (may be Jenkins-specific or standard)
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        val parseResult = parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                useRecursiveVisitor = true,
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
        logger.debug("Transient compilation for: $uri (phase=$compilePhase)")
        val sourcePath = runCatching { Path.of(uri) }.getOrNull()
        val classpath = workspaceManager.getClasspathForFile(uri, content)

        return parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = classpath,
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = workspaceManager.getWorkspaceSources(),
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                useRecursiveVisitor = true,
                compilePhase = compilePhase,
            ),
        )
    }

    fun getParseResult(uri: URI): com.github.albertocavalcante.groovyparser.api.ParseResult? = cache.get(uri)

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

        val parseResult = parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
                classpath = workspaceManager.getDependencyClasspath(),
                sourceRoots = workspaceManager.getSourceRoots(),
                workspaceSources = emptyList(), // Don't recurse during indexing
                locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                useRecursiveVisitor = false, // Faster for indexing - don't need full AST traversal
            ),
        )

        val astModel = parseResult.astModel
        if (astModel != null) {
            val index = SymbolIndex().buildFromVisitor(astModel)
            symbolStorageCache.put(uri, index)
            logger.debug("Indexed workspace file: $uri")
            index
        } else {
            logger.debug("Failed to build AST model for indexing: $uri")
            null
        }
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

        // Index files in truly parallel batches using launch
        // NOTE: Batch size balances parallelism with resource usage
        uris.chunked(INDEXING_BATCH_SIZE).forEach { batch ->
            coroutineScope {
                batch.forEach { uri ->
                    launch(Dispatchers.IO) {
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

        // Start new compilation
        val deferred = scope.async(Dispatchers.IO) {
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
                    kotlinx.coroutines.delay(50)
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
            compilationUnit = parseResult.compilationUnit,
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
