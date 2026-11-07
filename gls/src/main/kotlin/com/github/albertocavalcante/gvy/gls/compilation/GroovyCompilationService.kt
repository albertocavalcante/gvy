package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.engine.api.LanguageSession
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.codehaus.groovy.control.Phases
import java.net.URI
import java.nio.file.Path

/**
 * Facade service for Groovy compilation, delegating to specialized services for caching,
 * symbol indexing, AST access, engine lifecycle, and orchestration.
 */
class GroovyCompilationService(
    parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    documentProvider: DocumentProvider? = null,
    sourceNavigator: SourceNavigator? = null,
    engineConfig: EngineConfiguration = EngineConfiguration(),
    compilationCacheMaxEntries: Int = CompilationCache.DEFAULT_MAX_ENTRIES,
) {
    private val logger = KotlinLogging.logger {}
    private val errorHandler = CompilationErrorHandler()
    private val parser = GroovyParserFacade(parentClassLoader)
    private val workerSessionManager = WorkerSessionManager(
        defaultSession = InProcessWorkerSession(parser),
        sessionFactory = { InProcessWorkerSession(parser) },
    )

    // Specialized services
    private val isOpen: (URI) -> Boolean = { uri -> documentProvider?.get(uri) != null }
    private val cacheService = CompilationCacheService(maxEntries = compilationCacheMaxEntries, isOpen = isOpen)
    val workspaceManager = WorkspaceManager()
    private val symbolIndexer = SymbolIndexingService(ioDispatcher, workerSessionManager, workspaceManager, isOpen)
    private val parseAccessor = ParseResultAccessor(cacheService, workerSessionManager, workspaceManager, symbolIndexer)
    val workspaceScanner = WorkspaceScanner()
    val classpathService = ClasspathService()
    val gdkProvider = GroovyGdkProvider(classpathService)

    // Semantic database and workspace symbol index for cross-file resolution
    // These must be created before WorkspaceCompiler to ensure the same instance is shared
    private val semanticDb = GroovySemanticDB()
    private val workspaceSymbolIndex = WorkspaceSymbolIndex(semanticDb)
    private val semanticIndexer = SemanticIndexer(workspaceSymbolIndex, isOpen)
    private val semanticSnapshotGuard = SemanticSnapshotGuard { workspaceSymbolIndex.implementationSnapshot }

    // Shared dependency graph for bounded workspace selection (Issue #743)
    private val dependencyGraph = DependencyGraph()
    private val workspaceCompiler = WorkspaceCompiler(
        workerSessionManager,
        workspaceManager,
        semanticIndexer,
        dependencyGraph = dependencyGraph,
    )

    // Managers that handle complex lifecycle and orchestration
    private val engineManager = LanguageEngineManager(
        this,
        parser,
        parentClassLoader,
        workspaceManager,
        LanguageEngineManagerOptions(
            documentProvider = documentProvider,
            sourceNavigator = sourceNavigator,
            engineConfig = engineConfig,
        ),
    )
    private val resultMapper = CompilationResultMapper()
    private val orchestrator = CompilationOrchestrator(
        CompilationOrchestratorDependencies(
            cacheService = cacheService,
            workerSessionManager = workerSessionManager,
            workspaceManager = workspaceManager,
            symbolIndexer = symbolIndexer,
            parseAccessor = parseAccessor,
            resultMapper = resultMapper,
            ioDispatcher = ioDispatcher,
            errorHandler = errorHandler,
            semanticIndexer = semanticIndexer,
            dependencyGraph = dependencyGraph,
            documentProvider = documentProvider,
        ),
    )

    // ==========================================================================
    // Core Compilation API (Delegated to Orchestrator)
    // ==========================================================================

    suspend fun compile(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION) =
        orchestrator.compile(uri, content, compilePhase)

    suspend fun compileTransient(uri: URI, content: String, compilePhase: Int = Phases.CANONICALIZATION) =
        orchestrator.compileTransient(uri, content, compilePhase)

    fun compileAsync(scope: CoroutineScope, uri: URI, content: String) = orchestrator.compileAsync(scope, uri, content)

    suspend fun ensureCompiled(uri: URI) = orchestrator.ensureCompiled(uri)

    // ==========================================================================
    // Engine and Configuration API (Delegated to EngineManager)
    // ==========================================================================

    fun updateEngineConfiguration(newConfig: EngineConfiguration) = engineManager.updateEngineConfiguration(newConfig)

    fun getSession(uri: URI): LanguageSession? {
        val cached = cacheService.getCachedWithContent(uri) ?: return null
        val (content, _) = cached
        return engineManager.activeEngine.createSession(uri, content)
    }

    fun updateGroovyVersion(info: GroovyVersionInfo) = engineManager.updateGroovyVersion(info)

    fun getGroovyVersionInfo(): GroovyVersionInfo? = engineManager.getGroovyVersionInfo()

    fun updateSelectedWorker(worker: WorkerDescriptor?): Boolean {
        val changed = engineManager.getSelectedWorker() != worker
        if (changed) {
            engineManager.updateSelectedWorker(worker)
            workerSessionManager.select(worker)
            clearCaches()
            logger.info { "Worker changed to ${worker?.id}; cleared compilation caches" }
        }
        return changed
    }

    fun getSelectedWorker(): WorkerDescriptor? = engineManager.getSelectedWorker()

    fun invalidateClassLoader() = engineManager.invalidateClassLoader()

    // ==========================================================================
    // Workspace Compilation API
    // ==========================================================================

    /**
     * Gets the workspace compiler for multi-file compilation.
     * Enables full cross-file semantic resolution.
     */
    fun getWorkspaceCompiler(): WorkspaceCompiler = workspaceCompiler

    /**
     * Gets the workspace symbol index for cross-file member resolution.
     * Provides fast lookup for symbols, references, and class members across the workspace.
     *
     * @return The workspace symbol index, or null if not available
     */
    fun getWorkspaceSymbolIndex(): WorkspaceSymbolIndex? = workspaceSymbolIndex

    internal fun beginImplementationQuery(): SemanticSnapshotGuard.Generation = semanticSnapshotGuard.begin()

    internal fun captureImplementationSnapshot(generation: SemanticSnapshotGuard.Generation) =
        semanticSnapshotGuard.capture(generation)

    fun findClasspathClass(className: String): URI? {
        val classLoader = engineManager.getOrCreateClassLoader()
        val resourceName = className.replace('.', '/') + ".class"
        val resource = classLoader.getResource(resourceName) ?: return null
        return try {
            resource.toURI()
        } catch (e: Exception) {
            logger.warn(e) { "Invalid classpath resource URI: $resource" }
            null
        }
    }

    // ==========================================================================
    // Workspace and Cache API
    // ==========================================================================

    fun clearCaches() = semanticSnapshotGuard.invalidate {
        cacheService.clear()
        symbolIndexer.clear()
        semanticIndexer.clear()
        invalidateClassLoader()
    }

    fun invalidateCache(uri: URI) = semanticSnapshotGuard.invalidate {
        cacheService.invalidate(uri)
        symbolIndexer.invalidate(uri)
        semanticIndexer.remove(uri)
        logger.debug { "Invalidated cache for: $uri" }
    }

    /**
     * Re-derives the semantic document of [uri] from disk: after its buffer was closed, or after the file
     * changed. Drops it when there is no file (an `untitled:` buffer). An open buffer's document is kept.
     */
    suspend fun refreshSemanticFromDisk(uris: Collection<URI>) = workspaceCompiler.refreshFromDisk(uris)

    fun getCacheStatistics() = cacheService.getStatistics()

    /** Applies a new compilation cache cap; closed files beyond it are evicted and recompiled on demand. */
    fun updateCompilationCacheLimit(maxEntries: Int) = cacheService.resize(maxEntries)

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

    // ==========================================================================
    // Delegation Methods for Backward Compatibility
    // ==========================================================================

    fun getAst(uri: URI) = parseAccessor.getAst(uri)

    /** The source text the cached AST for [uri] was compiled from, so AST positions index into it. */
    fun getCompiledContent(uri: URI): String? = cacheService.getCachedWithContent(uri)?.first

    /** Capture source, AST model, and symbol table from the same cache entry under its existing lock. */
    internal fun getCompiledSnapshot(uri: URI): CompiledDocumentSnapshot? =
        cacheService.getCachedWithContent(uri)?.let { (source, parsed) -> CompiledDocumentSnapshot(source, parsed) }

    fun getParseResult(uri: URI) = parseAccessor.getParseResult(uri)
    fun getDiagnostics(uri: URI) = parseAccessor.getDiagnostics(uri)
    fun getTokenIndex(uri: URI) = parseAccessor.getTokenIndex(uri)
    fun getAstModel(uri: URI) = parseAccessor.getAstModel(uri)
    fun getSymbolTable(uri: URI) = parseAccessor.getSymbolTable(uri)
    suspend fun getValidParseResult(uri: URI) = parseAccessor.getValidParseResult(uri)
    fun getSymbolStorage(uri: URI) = symbolIndexer.getSymbolIndex(uri)
    fun getAllSymbolStorages() = symbolIndexer.getAllSymbolIndices()
    suspend fun indexAllWorkspaceSources(uris: List<URI>, onProgress: (Int, Int) -> Unit = { _, _ -> }) =
        symbolIndexer.indexAllWorkspaceSources(uris, onProgress)

    // ==========================================================================
    // Exposed services for specialized consumers
    // ==========================================================================
    val compilationCacheService: CompilationCacheService get() = cacheService
    val symbolIndexingService: SymbolIndexingService get() = symbolIndexer
    val parseResultAccessor: ParseResultAccessor get() = parseAccessor
}
