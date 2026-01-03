package com.github.albertocavalcante.groovylsp.compilation

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
import com.github.albertocavalcante.groovyparser.api.ParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.codehaus.groovy.control.Phases
import org.slf4j.LoggerFactory
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
) {
    private val logger = LoggerFactory.getLogger(GroovyCompilationService::class.java)
    private val errorHandler = CompilationErrorHandler()
    private val parser = GroovyParserFacade(parentClassLoader)
    private val workerSessionManager = WorkerSessionManager(
        defaultSession = InProcessWorkerSession(parser),
        sessionFactory = { InProcessWorkerSession(parser) },
    )

    // Specialized services
    private val cacheService = CompilationCacheService(ioDispatcher)
    val workspaceManager = WorkspaceManager()
    private val symbolIndexer = SymbolIndexingService(ioDispatcher, workerSessionManager, workspaceManager)
    private val parseAccessor = ParseResultAccessor(cacheService, workerSessionManager, workspaceManager)
    val workspaceScanner = WorkspaceScanner(ioDispatcher)
    val classpathService = ClasspathService()
    val gdkProvider = GroovyGdkProvider(classpathService)

    // Managers that handle complex lifecycle and orchestration
    private val engineManager = LanguageEngineManager(
        this,
        parser,
        parentClassLoader,
        workspaceManager,
        documentProvider,
        sourceNavigator,
        engineConfig,
    )
    private val resultMapper = CompilationResultMapper()
    private val orchestrator = CompilationOrchestrator(
        cacheService,
        workerSessionManager,
        workspaceManager,
        symbolIndexer,
        parseAccessor,
        resultMapper,
        ioDispatcher,
        errorHandler,
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
            logger.info("Worker changed to ${worker?.id}; cleared compilation caches")
        }
        return changed
    }

    fun getSelectedWorker(): WorkerDescriptor? = engineManager.getSelectedWorker()

    fun invalidateClassLoader() = engineManager.invalidateClassLoader()

    fun findClasspathClass(className: String): URI? {
        val classLoader = engineManager.getOrCreateClassLoader()
        val resourceName = className.replace('.', '/') + ".class"
        val resource = classLoader.getResource(resourceName) ?: return null
        return try {
            resource.toURI()
        } catch (e: Exception) {
            logger.warn("Invalid classpath resource URI: $resource", e)
            null
        }
    }

    // ==========================================================================
    // Workspace and Cache API
    // ==========================================================================

    fun clearCaches() {
        cacheService.clear()
        symbolIndexer.clear()
        invalidateClassLoader()
    }

    fun invalidateCache(uri: URI) {
        cacheService.invalidate(uri)
        symbolIndexer.invalidate(uri)
        logger.debug("Invalidated cache for: $uri")
    }

    fun getCacheStatistics() = cacheService.getStatistics()

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

    // ==========================================================================
    // Delegation Methods for Backward Compatibility
    // ==========================================================================

    fun getAst(uri: URI) = parseAccessor.getAst(uri)
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
