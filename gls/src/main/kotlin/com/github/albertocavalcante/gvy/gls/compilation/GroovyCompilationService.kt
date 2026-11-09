package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.engine.api.LanguageSession
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.impl.native.NativeLanguageEngine
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionInfo
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.codehaus.groovy.control.Phases
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Facade service for Groovy compilation, delegating to specialized services for caching,
 * symbol indexing, AST access, engine lifecycle, and orchestration.
 */
@Suppress("LongParameterList") // all defaulted; the session decorator is a test seam for compile timing
class GroovyCompilationService(
    parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    documentProvider: DocumentProvider? = null,
    sourceNavigator: SourceNavigator? = null,
    engineConfig: EngineConfiguration = EngineConfiguration(),
    compilationCacheMaxEntries: Int = CompilationCache.DEFAULT_MAX_ENTRIES,
    /** Wraps every parser session, so a test can hold a compile at an exact point. */
    workerSessionDecorator: (WorkerSession) -> WorkerSession = { it },
) {
    private val logger = KotlinLogging.logger {}
    private val errorHandler = CompilationErrorHandler()

    @Volatile
    var serverConfiguration: ServerConfiguration =
        ServerConfiguration()
        private set

    internal fun updateServerConfiguration(configuration: ServerConfiguration) {
        serverConfiguration = configuration
    }

    private val parser = GroovyParserFacade(parentClassLoader)
    private val workerSessionManager = WorkerSessionManager(
        defaultSession = workerSessionDecorator(InProcessWorkerSession(parser)),
        sessionFactory = { workerSessionDecorator(InProcessWorkerSession(parser)) },
    )

    // Specialized services
    private val isOpen: (URI) -> Boolean = { uri -> documentProvider?.get(uri) != null }

    // One publication fence for every artifact derived from a file (#1184)
    private val ownership = ArtifactOwnership()
    private val cacheService =
        CompilationCacheService(maxEntries = compilationCacheMaxEntries, isOpen = isOpen, ownership = ownership)
    val workspaceManager = WorkspaceManager()
    private val symbolIndexer =
        SymbolIndexingService(ioDispatcher, workerSessionManager, workspaceManager, ownership, isOpen)
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
        ownership = ownership,
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

    /** Compiles a buffer [snapshot] the caller read itself, with [ensureCompiled]'s retirement policy. */
    suspend fun ensureCompiled(uri: URI, snapshot: DocumentSnapshot) = orchestrator.ensureCompiled(uri, snapshot)

    // ==========================================================================
    // Engine and Configuration API (Delegated to EngineManager)
    // ==========================================================================

    fun updateEngineConfiguration(newConfig: EngineConfiguration) = engineManager.updateEngineConfiguration(newConfig)

    fun getSession(uri: URI): LanguageSession? {
        val cached =
            cacheService.getCachedWithContent(uri, workspaceManager.getConfigurationFingerprint()) ?: return null
        val (content, _) = cached
        return engineManager.activeEngine.createSession(uri, content)
    }

    /** Retains a request's source and native parse together, independently of later cache changes. */
    internal fun getHoverSession(uri: URI, snapshot: CompiledDocumentSnapshot): LanguageSession {
        val engine = engineManager.activeEngine
        return if (engine is NativeLanguageEngine) {
            engine.createHoverSession(snapshot.parseResult, uri.toString(), snapshot.sourceText)
        } else {
            engine.createSession(uri, snapshot.sourceText)
        }
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

    /** The compile phase the semantic document of [uri] was built at, or null when none is stored. */
    fun semanticPhaseOf(uri: URI): Int? = semanticIndexer.phaseOf(uri)

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

    /**
     * Retires every file's artifacts: work that started earlier can no longer publish, and pending jobs are
     * cancelled. Nothing new can be published between the separate removals.
     */
    fun clearCaches() = semanticSnapshotGuard.invalidate {
        ownership.retireAll {
            cacheService.clear()
            symbolIndexer.clear()
            semanticIndexer.clear()
        }
        invalidateClassLoader()
    }

    /** Retires [uri]'s artifacts, as [clearCaches] does for every file. */
    fun invalidateCache(uri: URI) = semanticSnapshotGuard.invalidate {
        ownership.retire(uri) {
            cacheService.invalidate(uri)
            symbolIndexer.invalidate(uri)
            semanticIndexer.remove(uri)
        }
        logger.debug { "Invalidated cache for: $uri" }
    }

    /**
     * A buffer for [uri] opened: work begun under an earlier lifecycle (a disk compile, a delayed close
     * cleanup) can no longer publish. The cached facts stay; content-keyed reads still reuse them.
     */
    fun onDocumentOpened(uri: URI) {
        ownership.advance(uri)
    }

    /**
     * The buffer for [uri] closed, or the closed file changed on disk: its cached parse no longer describes
     * the file, so content-less readers must not see it, and in-flight semantic publications of the old text
     * are retired. Symbol and semantic facts are kept until refreshed from disk under the returned ticket.
     * Returns null, retiring nothing, when a buffer is open again. [keepIfUnchanged] (one closed buffer) reads
     * the file and keeps a parse that matches it; watcher batches pass false and always retire.
     */
    fun onClosedSourceChanged(uri: URI, keepIfUnchanged: Boolean = true): ArtifactOwnership.Ticket? {
        // Read outside the fence. A buffer closed unchanged keeps its parse: it already describes the file.
        // Watcher batches skip the read (thousands of events on a branch switch) and simply retire the parse.
        val disk = UriPathConverter.toPath(uri)?.takeIf { keepIfUnchanged }?.let { path ->
            runCatching { Files.readString(path) }.getOrElse { error ->
                rethrowIfCancellationOrError(error)
                null
            }
        }
        val fingerprint = workspaceManager.getConfigurationFingerprint()
        return ownership.retireClosed(uri, isOpen) {
            if (disk == null || cacheService.getCachedWithContent(uri, fingerprint)?.first != disk) {
                cacheService.invalidate(uri)
            }
            semanticIndexer.retirePublications(uri)
        }
    }

    /** Stops background work owned by the compilation service (server shutdown). */
    fun shutdown() = orchestrator.shutdown()

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
    fun getCompiledContent(uri: URI): String? =
        cacheService.getCachedWithContent(uri, workspaceManager.getConfigurationFingerprint())?.first

    /** Capture source, AST model, and symbol table from the same cache entry under its existing lock. */
    internal fun getCompiledSnapshot(uri: URI): CompiledDocumentSnapshot? =
        cacheService.getCachedWithContent(uri, workspaceManager.getConfigurationFingerprint())
            ?.let { (source, parsed) -> CompiledDocumentSnapshot(source, parsed) }

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
