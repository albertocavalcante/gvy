// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.TestCommand
import com.github.albertocavalcante.gvy.build.bsp.BspBuildTool
import com.github.albertocavalcante.gvy.build.gradle.GradleBuildTool
import com.github.albertocavalcante.gvy.build.gradle.GradleConnectionPool
import com.github.albertocavalcante.gvy.build.maven.MavenBuildTool
import com.github.albertocavalcante.gvy.gls.compilation.ClasspathRestamper
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.config.ServerCapabilitiesFactory
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsRequestHandler
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsRunResult
import com.github.albertocavalcante.gvy.gls.project.DefaultProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.providers.ast.AstParams
import com.github.albertocavalcante.gvy.gls.providers.ast.AstRequestHandler
import com.github.albertocavalcante.gvy.gls.providers.ast.AstResult
import com.github.albertocavalcante.gvy.gls.providers.coverage.GetCoverageParams
import com.github.albertocavalcante.gvy.gls.providers.dependencies.DependenciesResult
import com.github.albertocavalcante.gvy.gls.providers.dependencies.DependencyRequestHandler
import com.github.albertocavalcante.gvy.gls.providers.dependencies.GetDependenciesParams
import com.github.albertocavalcante.gvy.gls.providers.indexing.ExportIndexParams
import com.github.albertocavalcante.gvy.gls.providers.testing.BuildToolInfo
import com.github.albertocavalcante.gvy.gls.providers.testing.DiscoverTestsParams
import com.github.albertocavalcante.gvy.gls.providers.testing.GetBuildToolInfoParams
import com.github.albertocavalcante.gvy.gls.providers.testing.GetTestResultsParams
import com.github.albertocavalcante.gvy.gls.providers.testing.RunTestParams
import com.github.albertocavalcante.gvy.gls.providers.testing.TestRequestDelegate
import com.github.albertocavalcante.gvy.gls.providers.testing.TestSuite
import com.github.albertocavalcante.gvy.gls.services.CoreEngineDeprecation
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.services.GroovyWorkspaceService
import com.github.albertocavalcante.gvy.gls.services.Health
import com.github.albertocavalcante.gvy.gls.services.IndexExportService
import com.github.albertocavalcante.gvy.gls.services.ManagedSourceDocumentService
import com.github.albertocavalcante.gvy.gls.services.ProjectStartupManager
import com.github.albertocavalcante.gvy.gls.services.StatusNotification
import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.sources.SourceAttachParams
import com.github.albertocavalcante.gvy.gls.sources.SourceBrowserRequestHandler
import com.github.albertocavalcante.gvy.gls.sources.SourceCommandService
import com.github.albertocavalcante.gvy.gls.sources.SourceContentResult
import com.github.albertocavalcante.gvy.gls.sources.SourceDefinitionParams
import com.github.albertocavalcante.gvy.gls.sources.SourceDetailsResult
import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentParams
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceResolveParams
import com.github.albertocavalcante.gvy.gls.sources.SourceResolveResult
import com.github.albertocavalcante.gvy.gls.sources.shutdownResources
import com.github.albertocavalcante.gvy.gls.transport.lsp4j.configurationPayload
import com.github.albertocavalcante.gvy.gls.verification.observeRuntime
import com.github.albertocavalcante.gvy.gls.verification.runtimeFacts
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.junit4.JUnit4TestDetector
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.gvy.testing.spock.SpockTestDetector
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.reports.coverage.model.CoverageResponse
import com.github.albertocavalcante.reports.results.model.TestResultsResponse
import com.google.gson.Gson
import com.google.gson.JsonElement
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS = 15L
private const val PARENT_EXIT_SHUTDOWN_TIMEOUT_SECONDS = 20L

class GroovyLanguageServer(
    private val parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val processLiveness: ProcessLiveness = SystemProcessLiveness,
    private val exitProcess: (Int) -> Unit = { kotlin.system.exitProcess(it) },
    private val shutdownExecutor: Executor = ForkJoinPool.commonPool(),
) : LanguageServer,
    LanguageClientAware {

    private val logger = KotlinLogging.logger {}

    // Base client for standard LSP notifications (showMessage, publishDiagnostics, etc.)
    private var baseClient: LanguageClient? = null

    // Extended client for Groovy-specific notifications (groovy/status). May be null if
    // the client doesn't support the GroovyLanguageClient interface.
    private var groovyClient: GroovyLanguageClient? = null

    private val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    // Exits the server when the editor process dies, so the JVM is not orphaned.
    // The callback runs on its own daemon thread: a parent that is already dead completes the
    // watch immediately, and that must not block `initialize` while shutdown runs.
    private val parentWatchdog = ParentProcessWatchdog(processLiveness) {
        Thread({ handleParentExit() }, "gls-parent-exit").apply { isDaemon = true }.start()
    }

    /** True once the client has requested `shutdown` (or the parent died and we shut down). */
    @Volatile
    internal var shutdownRequested = false
        private set

    private fun handleParentExit() {
        // shutdown() is idempotent, so a prior client-requested shutdown is not repeated.
        // LSP convention: exit code 0 only if shutdown was requested before exit.
        val clean = shutdownRequested
        runCatching { shutdown().get(PARENT_EXIT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { logger.warn(it) { "Shutdown after parent exit did not complete cleanly" } }
        exitProcess(if (clean) 0 else 1)
    }

    // Shared Services
    private val documentProvider = DocumentProvider()

    @Volatile
    private var sourceBrowserEnabled = false

    private val externalSources = ExternalSourceRegistry(virtualDocumentsEnabled = { sourceBrowserEnabled })
    private val sourceNavigator = SourceNavigationService(sourcePresentation = externalSources)

    private val compilationService = GroovyCompilationService(
        parentClassLoader = parentClassLoader,
        documentProvider = documentProvider,
        sourceNavigator = sourceNavigator,
        workspaceManager = WorkspaceManager(sourceExclusion = { externalSources.isManaged(it.toUri()) }),
    )
    private val sourceCommands = SourceCommandService(
        compilationService,
        documentProvider,
        sourceNavigator,
        externalSources,
    )
    private val sourceBrowser = SourceBrowserRequestHandler(
        coroutineScope,
        externalSources,
        sourceCommands::openLookupSession,
        resolveSource = sourceCommands::resolve,
        attachSource = sourceCommands::attach,
    )

    // Services
    private val textDocumentService = GroovyTextDocumentService(
        coroutineScope = coroutineScope,
        compilationService = compilationService,
        options = GroovyTextDocumentServiceOptions(
            client = { baseClient },
            documentProvider = documentProvider,
            sourceNavigator = sourceNavigator,
        ),
    )
    private val managedTextDocumentService = ManagedSourceDocumentService(
        textDocumentService,
        externalSources,
        sourceBrowser::definition,
        definitionLinkSupport = { clientCapabilities?.textDocument?.definition?.linkSupport == true },
    )

    private val diagnosticsRequestHandler = DiagnosticsRequestHandler(
        coroutineScope,
        textDocumentService::awaitCurrentDiagnostics,
    )

    /** Notices classpath entries replaced between dependency observations (#1364). */
    private val classpathRestamper = ClasspathRestamper(compilationService, coroutineScope)
    private val coreEngineDeprecation = CoreEngineDeprecation()
    private val workspaceService = GroovyWorkspaceService(
        compilationService,
        compilationService.symbolIndexingService,
        coroutineScope,
        textDocumentService,
        classpathRestamper = classpathRestamper,
        reportEngineSetting = coreEngineDeprecation::report,
        onBuildFilesChanged = { paths -> startupManager.dependencyManager?.onWatchedFilesChanged(paths) },
    )

    // Helpers
    private val availableBuildTools: List<BuildTool> = listOf(
        BspBuildTool(),
        GradleBuildTool(),
        MavenBuildTool(),
    )
    private val strategyRegistry = ProjectStrategyRegistry(
        listOf(
            JenkinsProjectStrategy(coroutineScope),
            DefaultProjectStrategy(),
        ),
    )
    private val startupManager = ProjectStartupManager(
        compilationService,
        availableBuildTools,
        coroutineScope,
        strategyRegistry,
        classpathRestamper = classpathRestamper,
        reportEngineSetting = coreEngineDeprecation::report,
    )
    private val testRequestDelegate =
        TestRequestDelegate(
            coroutineScope,
            compilationService,
            buildToolManagerProvider = { startupManager.buildToolManager },
        )
    private val dependencyRequestHandler =
        DependencyRequestHandler(
            coroutineScope,
            buildToolManagerProvider = { startupManager.buildToolManager },
            workspaceRootProvider = { savedInitParams?.let { startupManager.getWorkspaceRoot(it) } },
        )
    private val indexExportService = IndexExportService(
        sourceExclusion = { compilationService.workspaceManager.isExcludedSource(it) },
    ) { startupManager.buildToolManager }
    private val astRequestHandler = AstRequestHandler(
        compilationService,
        CoreAstConverter(),
        NativeAstConverter(),
    )

    // State
    @Volatile
    private var implementationRequestsReady = false
    private var savedInitParams: InitializeParams? = null
    private var clientCapabilities: ClientCapabilities? = null
    private var savedInitOptionsMap: Map<String, Any>? = null

    init {
        // Register test framework detectors
        TestFrameworkRegistry.default.registerIfAbsent(SpockTestDetector())
        TestFrameworkRegistry.default.registerIfAbsent(JUnit5TestDetector())
        TestFrameworkRegistry.default.registerIfAbsent(JUnit4TestDetector())
    }

    override fun connect(client: LanguageClient) {
        logger.info { "Connected to language client" }
        // Always store base client for standard LSP operations
        this.baseClient = client

        // Try to cast to GroovyLanguageClient for extended notifications.
        // LSP4J creates a proxy that implements all interfaces registered on the Launcher.
        this.groovyClient = client as? GroovyLanguageClient
        if (this.groovyClient == null) {
            logger.info { "Client does not support GroovyLanguageClient interface - status notifications disabled" }
        }
    }

    /**
     * Sends a status notification to the client.
     *
     * Provides explicit server status updates for clients to track server state.
     * Based on rust-analyzer's `experimental/serverStatus` notification pattern.
     *
     * @param notification The status notification to send to the client.
     */
    internal fun sendStatus(notification: StatusNotification = StatusNotification()) {
        try {
            groovyClient?.groovyStatus(notification)
            logger.debug {
                "Sent status notification: health=${notification.health}, " +
                    "quiescent=${notification.quiescent}, message=${notification.message}"
            }
        } catch (e: Exception) {
            logger.debug { "Could not send status notification: ${e.message}" }
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        implementationRequestsReady = false
        logger.info { "Initializing Groovy Language Server..." }
        logger.info { Version.info.startupLine() }
        logger.info { "Client: ${params.clientInfo?.name ?: "Unknown"}" }
        logger.info { "Root URI: ${params.workspaceFolders?.firstOrNull()?.uri ?: "None"}" }

        savedInitParams = params
        textDocumentService.updateWorkspaceFolders(workspaceFolderPaths(params))
        parentWatchdog.watch(params.processId)
        clientCapabilities = params.capabilities

        val linkSupport = params.capabilities?.textDocument?.definition?.linkSupport == true
        textDocumentService.updateDefinitionLinkSupport(linkSupport)
        textDocumentService.updateLabelDetailsSupport(
            params.capabilities?.textDocument?.completion?.completionItem?.labelDetailsSupport == true,
        )

        savedInitOptionsMap = parseInitOptions(params.initializationOptions)
        sourceBrowserEnabled = savedInitOptionsMap?.get("groovy.sourceBrowser.enabled") == true
        textDocumentService.updateConfiguration(ServerConfiguration.fromMap(savedInitOptionsMap))

        val initializeResult = ServerCapabilitiesFactory.createInitializeResult(
            prepareRenameSupport = params.capabilities?.textDocument?.rename?.prepareSupport == true,
        )
        initializeResult.capabilities.experimental = mapOf("groovySourceBrowser" to mapOf("version" to 1))

        implementationRequestsReady = true
        logger.info { "LSP initialized - ready for requests" }
        return CompletableFuture.completedFuture(initializeResult)
    }

    private fun workspaceFolderPaths(params: InitializeParams): List<java.nio.file.Path> =
        params.workspaceFolders.orEmpty().mapNotNull { folder ->
            runCatching { java.nio.file.Paths.get(URI.create(folder.uri)) }.getOrNull()
        }

    private fun parseInitOptions(options: Any?): Map<String, Any> {
        if (options == null) return emptyMap()
        return configurationPayload(options) ?: run {
            logger.warn { "Invalid initialization options type or content: ${options.javaClass.name}" }
            emptyMap()
        }
    }

    override fun initialized(params: InitializedParams) {
        logger.info { "Server initialized - starting async dependency resolution" }

        // Send starting status before async work begins
        sendStatus(StatusNotification(health = Health.Ok, quiescent = false, message = "Initializing workspace..."))

        startupManager.registerFileWatchers(baseClient, clientCapabilities)
        // A tick does nothing until target evidence is published, and ends with the server's scope.
        classpathRestamper.start()

        baseClient?.showMessage(
            MessageParams().apply {
                type = MessageType.Info
                message = "Groovy Language Server is ready!"
            },
        )

        startupManager.startAsyncDependencyResolution(
            client = baseClient,
            initParams = savedInitParams,
            initOptionsMap = savedInitOptionsMap,
            textDocumentServiceRefresh = { textDocumentService.refreshOpenDocuments() },
            onStatusUpdate = { health, quiescent, message, filesIndexed, filesTotal, errorCode, errorDetails ->
                sendStatus(
                    StatusNotification(health, quiescent, message, filesIndexed, filesTotal, errorCode, errorDetails),
                )
            },
        )
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested = true
        return shutdownFuture
    }

    // Created at most once, so repeated shutdown requests (client plus watchdog) share one run.
    private val shutdownFuture: CompletableFuture<Any> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CompletableFuture.supplyAsync({ doShutdown() }, shutdownExecutor)
    }

    private fun doShutdown(): Any {
        logger.info { "Shutting down Groovy Language Server..." }
        try {
            shutdownResources(
                { startupManager.shutdown() },
                {
                    val poolShutdown = CompletableFuture.runAsync { GradleConnectionPool.shutdown() }
                    try {
                        poolShutdown.get(GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (e: TimeoutException) {
                        logger.warn {
                            "Gradle connection pool shutdown exceeded " +
                                "$GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS seconds; continuing shutdown"
                        }
                        poolShutdown.cancel(true)
                    }
                },
                sourceBrowser::close,
                // Requests first, so none sees the compilation shutdown as a retirement to report.
                { coroutineScope.cancel() },
                sourceNavigator::close,
                compilationService::shutdown,
            )
        } catch (e: CancellationException) {
            logger.debug(e) { "Coroutine scope cancelled during shutdown" }
        } catch (e: Exception) {
            logger.warn(e) { "Error during shutdown" }
        }
        return Any()
    }

    /** LSP: the server process exits with 0 if `shutdown` was requested first, otherwise 1. */
    override fun exit() {
        logger.info { "Exiting Groovy Language Server" }
        exitProcess(if (shutdownRequested) 0 else 1)
    }

    override fun getTextDocumentService(): TextDocumentService = managedTextDocumentService

    /** Internal diagnostics and readiness hooks; protocol requests use the guarded service above. */
    internal val workspaceDocumentService: GroovyTextDocumentService
        get() = textDocumentService

    @JsonRequest("groovy/sourceContent")
    fun sourceContent(params: SourceDocumentParams): CompletableFuture<SourceContentResult?> =
        sourceBrowser.content(params)

    @JsonRequest("groovy/sourceDefinition")
    fun sourceDefinition(params: SourceDefinitionParams): CompletableFuture<List<org.eclipse.lsp4j.LocationLink>> =
        sourceBrowser.definition(params)

    @JsonRequest("groovy/sourceDetails")
    fun sourceDetails(params: SourceDocumentParams): CompletableFuture<SourceDetailsResult?> =
        sourceBrowser.details(params)

    @JsonRequest("groovy/sourceResolve")
    fun sourceResolve(params: SourceResolveParams): CompletableFuture<SourceResolveResult> =
        sourceBrowser.resolve(params)

    @JsonRequest("groovy/sourceAttach")
    fun sourceAttach(params: SourceAttachParams): CompletableFuture<SourceResolveResult> = sourceBrowser.attach(params)

    @JsonRequest("groovy/sourceIsManaged")
    fun sourceIsManaged(params: SourceDocumentParams): CompletableFuture<Boolean> = sourceBrowser.isManaged(params)

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    // ============================================================================
    // CUSTOM LSP METHODS
    // ============================================================================

    /**
     * Versioned server runtime and current hover-engine facts. The engine is observed at request time and is not
     * an attribution to any earlier hover. Optional values are omitted unless their state is known.
     */
    @JsonRequest("groovy/runtimeFacts")
    fun runtimeFacts(): CompletableFuture<JsonElement> {
        val error = customRequestLifecycleError()
        if (error != null) {
            return CompletableFuture.failedFuture(ResponseErrorException(error))
        }
        val facts = runtimeFacts(Version.info, observeRuntime(), compilationService.observeHoverEngine())
        return CompletableFuture.completedFuture(Gson().toJsonTree(facts))
    }

    @JsonRequest("groovy/awaitDiagnostics")
    fun awaitDiagnostics(params: JsonElement?): CompletableFuture<DiagnosticsRunResult> {
        val error = customRequestLifecycleError()
        if (error != null) {
            return CompletableFuture.failedFuture(ResponseErrorException(error))
        }
        return diagnosticsRequestHandler.awaitDiagnostics(params)
    }

    private fun customRequestLifecycleError(): ResponseError? = when {
        shutdownRequested -> ResponseError(ResponseErrorCode.InvalidRequest, "Server is shutting down", null)
        !implementationRequestsReady ->
            ResponseError(ResponseErrorCode.ServerNotInitialized, "Server is not initialized", null)
        else -> null
    }

    @JsonRequest("groovy/discoverTests")
    fun discoverTests(params: DiscoverTestsParams): CompletableFuture<List<TestSuite>> =
        testRequestDelegate.discoverTests(params)

    @JsonRequest("groovy/runTest")
    fun runTest(params: RunTestParams): CompletableFuture<TestCommand> = testRequestDelegate.runTest(params)

    @JsonRequest("groovy/getBuildToolInfo")
    fun getBuildToolInfo(params: GetBuildToolInfoParams): CompletableFuture<BuildToolInfo> =
        testRequestDelegate.getBuildToolInfo(params)

    @JsonRequest("groovy/exportIndex")
    fun exportIndex(params: ExportIndexParams): CompletableFuture<String> = CompletableFuture.supplyAsync({
        val rootPath = savedInitParams?.let { startupManager.getWorkspaceRoot(it) }
            ?: error("No workspace root found")

        indexExportService.exportIndex(params, rootPath)
    }, dispatcher.asExecutor())

    @JsonRequest("groovy/ast")
    fun getAst(params: AstParams): CompletableFuture<AstResult> = astRequestHandler.getAst(params)

    @JsonRequest("groovy/getTestResults")
    fun getTestResults(params: GetTestResultsParams): CompletableFuture<TestResultsResponse> =
        testRequestDelegate.getTestResults(params)

    @JsonRequest("groovy/getCoverage")
    fun getCoverage(params: GetCoverageParams): CompletableFuture<CoverageResponse> =
        testRequestDelegate.getCoverage(params)

    @JsonRequest("groovy/workspace/dependencies")
    fun getDependencies(params: GetDependenciesParams): CompletableFuture<DependenciesResult> =
        dependencyRequestHandler.getDependencies(params)

    // Exposed for testing/CLI
    fun waitForDependencies(timeoutSeconds: Long = 60): Boolean = startupManager.waitForDependencies(timeoutSeconds)
}
