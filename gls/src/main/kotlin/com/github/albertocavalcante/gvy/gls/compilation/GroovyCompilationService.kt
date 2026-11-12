// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
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
class GroovyCompilationService internal constructor(
    parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    documentProvider: DocumentProvider? = null,
    sourceNavigator: SourceNavigator? = null,
    engineConfig: EngineConfiguration = EngineConfiguration(),
    compilationCacheMaxEntries: Int = CompilationCache.DEFAULT_MAX_ENTRIES,
    /** Wraps every parser session, so a test can hold a compile at an exact point. */
    workerSessionDecorator: (WorkerSession) -> WorkerSession = { it },
    /** The workspace model's owner; an internal seam so a test can interleave a model change deterministically. */
    workspaceManager: WorkspaceManager,
) {
    constructor(
        parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        documentProvider: DocumentProvider? = null,
        sourceNavigator: SourceNavigator? = null,
        engineConfig: EngineConfiguration = EngineConfiguration(),
        compilationCacheMaxEntries: Int = CompilationCache.DEFAULT_MAX_ENTRIES,
        workerSessionDecorator: (WorkerSession) -> WorkerSession = { it },
    ) : this(
        parentClassLoader,
        ioDispatcher,
        documentProvider,
        sourceNavigator,
        engineConfig,
        compilationCacheMaxEntries,
        workerSessionDecorator,
        WorkspaceManager(),
    )

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

    // Listed copies a buffer open under another spelling stands for (#1446): kept out of compiles and queries.
    private val shadows = ListedShadows(documentProvider, workspaceManager)
    private val cacheService =
        CompilationCacheService(maxEntries = compilationCacheMaxEntries, isOpen = isOpen, ownership = ownership)
    val workspaceManager = workspaceManager
    private val symbolIndexer =
        SymbolIndexingService(
            ioDispatcher,
            workerSessionManager,
            workspaceManager,
            ownership,
            shadowed = shadows::shadowed,
            isOpen = isOpen,
        )
    private val parseAccessor = ParseResultAccessor(cacheService, workerSessionManager, workspaceManager, symbolIndexer)
    val workspaceScanner = WorkspaceScanner()
    val classpathService = ClasspathService()
    val gdkProvider = GroovyGdkProvider(classpathService)

    // Semantic database and workspace symbol index for cross-file resolution
    // These must be created before WorkspaceCompiler to ensure the same instance is shared
    private val semanticDb = GroovySemanticDB()
    private val workspaceSymbolIndex = WorkspaceSymbolIndex(semanticDb, shadows::shadowed)
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
            shadowed = shadows::shadowed,
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

    /** Current configured and instantiated hover engine without creating an engine. */
    internal fun observeHoverEngine() = engineManager.observeHoverEngine()

    fun getSession(uri: URI): LanguageSession? {
        val cached =
            cacheService.getCachedWithContent(uri, workspaceManager.fingerprintFor(uri)) ?: return null
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

    /**
     * The buffer for [uri] closed: the listed spelling it shadowed, when the client named the file otherwise
     * (#1446). Null when [uri] is listed as it is, or names no file.
     */
    fun onShadowingDocumentClosed(uri: URI): URI? = shadows.closed(uri)

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
     *
     * Under the fence this only swaps references, so it is O(1) whatever the workspace size (#1342). The retired
     * pending jobs' waiters are woken and the jobs cancelled once no lock is held (#1429); one that is still
     * running when this returns is only wasted work, because its ticket belongs to the retired epoch.
     */
    fun clearCaches() = retireAll {
        symbolIndexer.clear()
        semanticIndexer.clear()
    }

    /**
     * Retires every file's tickets and drops the compiled results and the class loader, as [clearCaches] does, but
     * keeps each file's symbol and semantic facts: for a change to an input other than any file's own text (the
     * source list, a re-stamped classpath entry). Callers re-index only the files that changed, so dropping the
     * facts too would empty workspace symbols, class lookup and cross-file navigation for every closed file.
     *
     * A compile that read the old inputs still cannot publish: its cache and symbol writes hold a retired ticket,
     * and its semantic publication was retired with them. A kept fact describes its file's text, which did not
     * change; a deleted source's facts are dropped by the caller through [invalidateCache].
     */
    fun clearCompileCaches() = retireAll {
        symbolIndexer.unparseable.clear() // stale anyway: each entry carries a ticket retired here
        semanticIndexer.retireAllPublications()
    }

    /** Retires every ticket and the compiled results, running [alsoUnderFence] before another ticket is taken. */
    private fun retireAll(alsoUnderFence: () -> Unit) {
        var retired: Map<URI, PendingCompilation> = emptyMap()
        semanticSnapshotGuard.invalidate {
            // Only reference swaps run under the fence, so it is held for O(1) whatever the workspace size (#1342)
            ownership.retireAll {
                retired = cacheService.detach()
                alsoUnderFence()
            }
            invalidateClassLoader()
        }
        // Woken and cancelled once no lock is held and the class loader is gone: a waiter may resume inline and
        // re-resolve at once (#1429). A retired job still running is only wasted work: its ticket cannot commit.
        cacheService.cancelDetached(retired)
    }

    /**
     * Applies one file-watch notification's created and deleted paths, files or folders, to the workspace model
     * (#1422, #1424), returning the sources it added and removed so the caller can index and purge them.
     * When the source list changed, the compile caches are cleared once: the fingerprint covers the classpath
     * and roots, not the source list, so cached results would otherwise keep the old list's resolution. The
     * symbol and semantic facts of unchanged files are kept ([clearCompileCaches]).
     *
     * Order matters: the new model is published before [clearCompileCaches] retires every ticket. A compile takes
     * its ticket before it reads the model, so any compile that read the old list holds a ticket from
     * before the retirement and cannot publish; a compile after it reads the new list.
     */
    fun onWorkspaceSourcesChanged(created: Collection<Path>, deleted: Collection<Path>): SourceListChange {
        val change = workspaceManager.applySourceChanges(created, deleted)
        if (change.changed) {
            clearCompileCaches()
            shadows.refresh() // on the source queue: the next request finds the new listing's spellings ready
        }
        return change
    }

    /** Retires [uri]'s artifacts, as [clearCaches] does for every file. */
    fun invalidateCache(uri: URI) {
        var retired: PendingCompilation? = null
        semanticSnapshotGuard.invalidate {
            ownership.retire(uri) {
                retired = cacheService.detach(uri)
                symbolIndexer.invalidate(uri)
                semanticIndexer.remove(uri)
            }
        }
        // Woken once no lock is held: an inline waiter re-resolving inside the cleanup would lose its facts (#1429)
        retired?.retire()
        logger.debug { "Invalidated cache for: $uri" }
    }

    /**
     * A buffer for [uri] opened: work begun under an earlier lifecycle (a disk compile, a delayed close
     * cleanup) can no longer publish. The cached facts stay; content-keyed reads still reuse them.
     */
    fun onDocumentOpened(uri: URI) {
        ownership.advance(uri)
        shadows.opened(uri)
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
        // Only that comparison needs the fingerprint: a watcher batch skips it for every event (#1428).
        val fingerprint = disk?.let { workspaceManager.fingerprintFor(uri) }
        var retired: PendingCompilation? = null
        val ticket = ownership.retireClosed(uri, isOpen) {
            val unchanged = disk != null && fingerprint != null &&
                cacheService.getCachedWithContent(uri, fingerprint)?.first == disk
            if (!unchanged) retired = cacheService.detach(uri)
            semanticIndexer.retirePublications(uri)
        }
        // Woken after the fence, as [invalidateCache] does (#1429)
        retired?.retire()
        return ticket
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

    /**
     * Publishes the startup model for [workspaceRoot] ([WorkspaceManager.initializeWorkspace]); buffers opened
     * before it are spelled against its discovered roots now, off the request path (#1446).
     */
    fun initializeWorkspace(workspaceRoot: Path) {
        workspaceManager.initializeWorkspace(workspaceRoot)
        shadows.refresh()
    }

    fun updateWorkspaceModel(
        workspaceRoot: Path,
        dependencies: List<Path>,
        sourceDirectories: List<Path>,
        targets: TargetDependencyEvidence = TargetDependencyEvidence.Legacy,
    ) {
        val changed = workspaceManager.updateWorkspaceModel(workspaceRoot, dependencies, sourceDirectories, targets)
        if (changed) {
            // The published model's classpath: the same snapshot every compile now reads
            classpathService.updateClasspath(workspaceManager.current().classpath)
            gdkProvider.initialize()
            clearCaches()
            shadows.refresh() // off the request path: open buffers are spelled against the new roots now
        }
    }

    /**
     * Publishes re-stamped target evidence (#1364) through [WorkspaceManager.restampTargets]. When it lands, every
     * compile key of an affected target changes and the compile caches are cleared, class loader included, so a
     * replaced jar is not read through a stale open handle. Nothing re-indexes after a re-stamp, so the symbol and
     * semantic facts are kept ([clearCompileCaches]). Returns whether it landed.
     */
    fun restampTargets(expected: TargetDependencyEvidence.Scoped, refreshed: TargetDependencyEvidence.Scoped): Boolean {
        val published = workspaceManager.restampTargets(expected, refreshed)
        if (published) clearCompileCaches()
        return published
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
        cacheService.getCachedWithContent(uri, workspaceManager.fingerprintFor(uri))?.first

    /** Capture source, AST model, and symbol table from the same cache entry under its existing lock. */
    internal fun getCompiledSnapshot(uri: URI): CompiledDocumentSnapshot? =
        cacheService.getCachedWithContent(uri, workspaceManager.fingerprintFor(uri))
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
