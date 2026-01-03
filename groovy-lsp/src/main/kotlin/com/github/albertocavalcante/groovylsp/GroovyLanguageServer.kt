package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovyjunit.junit.JUnit5TestDetector
import com.github.albertocavalcante.groovyjunit.junit4.JUnit4TestDetector
import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.buildtool.bsp.BspBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleConnectionPool
import com.github.albertocavalcante.groovylsp.buildtool.maven.MavenBuildTool
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerCapabilitiesFactory
import com.github.albertocavalcante.groovylsp.providers.ast.AstParams
import com.github.albertocavalcante.groovylsp.providers.ast.AstRequestHandler
import com.github.albertocavalcante.groovylsp.providers.ast.AstResult
import com.github.albertocavalcante.groovylsp.providers.indexing.ExportIndexParams
import com.github.albertocavalcante.groovylsp.providers.testing.DiscoverTestsParams
import com.github.albertocavalcante.groovylsp.providers.testing.RunTestParams
import com.github.albertocavalcante.groovylsp.providers.testing.TestRequestDelegate
import com.github.albertocavalcante.groovylsp.providers.testing.TestSuite
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.services.GroovyLanguageClient
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import com.github.albertocavalcante.groovylsp.services.GroovyWorkspaceService
import com.github.albertocavalcante.groovylsp.services.Health
import com.github.albertocavalcante.groovylsp.services.IndexExportService
import com.github.albertocavalcante.groovylsp.services.ProjectStartupManager
import com.github.albertocavalcante.groovylsp.services.StatusNotification
import com.github.albertocavalcante.groovylsp.sources.SourceNavigationService
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import com.github.albertocavalcante.groovytesting.spock.SpockTestDetector
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
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
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS = 15L

class GroovyLanguageServer(
    private val parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : LanguageServer,
    LanguageClientAware {

    private val logger = LoggerFactory.getLogger(GroovyLanguageServer::class.java)

    // Base client for standard LSP notifications (showMessage, publishDiagnostics, etc.)
    private var baseClient: LanguageClient? = null

    // Extended client for Groovy-specific notifications (groovy/status). May be null if
    // the client doesn't support the GroovyLanguageClient interface.
    private var groovyClient: GroovyLanguageClient? = null

    private val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    // Shared Services
    private val documentProvider = DocumentProvider()
    private val sourceNavigator = SourceNavigationService()

    private val compilationService = GroovyCompilationService(
        parentClassLoader = parentClassLoader,
        documentProvider = documentProvider,
        sourceNavigator = sourceNavigator,
    )

    // Extracted services from compilation service
    private val parseResultAccessor = compilationService.parseResultAccessor
    private val symbolIndexer = compilationService.symbolIndexingService

    // Services
    private val textDocumentService = GroovyTextDocumentService(
        coroutineScope = coroutineScope,
        compilationService = compilationService,
        parseResultAccessor = parseResultAccessor,
        symbolIndexer = symbolIndexer,
        client = { baseClient },
        documentProvider = documentProvider,
        sourceNavigator = sourceNavigator,
    )
    private val workspaceService = GroovyWorkspaceService(
        compilationService,
        symbolIndexer,
        coroutineScope,
        textDocumentService,
    )

    // Helpers
    private val availableBuildTools: List<BuildTool> = listOf(
        BspBuildTool(),
        GradleBuildTool(),
        MavenBuildTool(),
    )
    private val startupManager = ProjectStartupManager(compilationService, availableBuildTools, coroutineScope)
    private val testRequestDelegate =
        TestRequestDelegate(
            coroutineScope,
            compilationService,
            buildToolManagerProvider = { startupManager.buildToolManager },
        )
    private val indexExportService = IndexExportService { startupManager.buildToolManager }
    private val astRequestHandler = AstRequestHandler(
        compilationService,
        CoreAstConverter(),
        NativeAstConverter(),
    )

    // State
    private var savedInitParams: InitializeParams? = null
    private var clientCapabilities: ClientCapabilities? = null
    private var savedInitOptionsMap: Map<String, Any>? = null

    init {
        // Register test framework detectors
        TestFrameworkRegistry.registerIfAbsent(SpockTestDetector())
        TestFrameworkRegistry.registerIfAbsent(JUnit5TestDetector())
        TestFrameworkRegistry.registerIfAbsent(JUnit4TestDetector())
    }

    override fun connect(client: LanguageClient) {
        logger.info("Connected to language client")
        // Always store base client for standard LSP operations
        this.baseClient = client

        // Try to cast to GroovyLanguageClient for extended notifications.
        // LSP4J creates a proxy that implements all interfaces registered on the Launcher.
        this.groovyClient = client as? GroovyLanguageClient
        if (this.groovyClient == null) {
            logger.info("Client does not support GroovyLanguageClient interface - status notifications disabled")
        }
    }

    /**
     * Sends a status notification to the client.
     *
     * Provides explicit server status updates for clients to track server state.
     * Based on rust-analyzer's `experimental/serverStatus` notification pattern.
     *
     * @param health Server functional state (ok, warning, error)
     * @param quiescent Whether there is any pending background work (false = busy, true = idle)
     * @param message Optional human-readable message
     * @param filesIndexed Current number of files indexed (for progress display)
     * @param filesTotal Total number of files to index (for progress display)
     */
    internal fun sendStatus(
        health: Health = Health.Ok,
        quiescent: Boolean = true,
        message: String? = null,
        filesIndexed: Int? = null,
        filesTotal: Int? = null,
    ) {
        val notification = StatusNotification(
            health = health,
            quiescent = quiescent,
            message = message,
            filesIndexed = filesIndexed,
            filesTotal = filesTotal,
        )
        try {
            groovyClient?.groovyStatus(notification)
            logger.debug("Sent status notification: health={}, quiescent={}, message={}", health, quiescent, message)
        } catch (e: Exception) {
            logger.debug("Could not send status notification: {}", e.message)
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing Groovy Language Server...")
        logger.info("Client: ${params.clientInfo?.name ?: "Unknown"}")
        logger.info("Root URI: ${params.workspaceFolders?.firstOrNull()?.uri ?: "None"}")

        savedInitParams = params
        clientCapabilities = params.capabilities
        @Suppress("UNCHECKED_CAST")
        savedInitOptionsMap = params.initializationOptions as? Map<String, Any>

        val initializeResult = ServerCapabilitiesFactory.createInitializeResult()

        logger.info("LSP initialized - ready for requests")
        return CompletableFuture.completedFuture(initializeResult)
    }

    override fun initialized(params: InitializedParams) {
        logger.info("Server initialized - starting async dependency resolution")

        // Send starting status before async work begins
        sendStatus(health = Health.Ok, quiescent = false, message = "Initializing workspace...")

        startupManager.registerFileWatchers(baseClient, clientCapabilities)

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
            onStatusUpdate = { health, quiescent, message, filesIndexed, filesTotal ->
                sendStatus(health, quiescent, message, filesIndexed, filesTotal)
            },
        )
    }

    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.supplyAsync {
        logger.info("Shutting down Groovy Language Server...")
        try {
            startupManager.shutdown()

            val poolShutdown = CompletableFuture.runAsync { GradleConnectionPool.shutdown() }
            try {
                poolShutdown.get(GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                logger.warn(
                    "Gradle connection pool shutdown exceeded {} seconds; continuing shutdown",
                    GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS,
                )
                poolShutdown.cancel(true)
            }

            coroutineScope.cancel()
        } catch (e: CancellationException) {
            logger.debug("Coroutine scope cancelled during shutdown", e)
        } catch (e: Exception) {
            logger.warn("Error during shutdown", e)
        }
        Any()
    }

    override fun exit() {
        logger.info("Exiting Groovy Language Server")
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService
    override fun getWorkspaceService(): WorkspaceService = workspaceService

    // ============================================================================
    // CUSTOM LSP METHODS
    // ============================================================================

    @JsonRequest("groovy/discoverTests")
    fun discoverTests(params: DiscoverTestsParams): CompletableFuture<List<TestSuite>> =
        testRequestDelegate.discoverTests(params)

    @JsonRequest("groovy/runTest")
    fun runTest(params: RunTestParams): CompletableFuture<TestCommand> = testRequestDelegate.runTest(params)

    @JsonRequest("groovy/exportIndex")
    fun exportIndex(params: ExportIndexParams): CompletableFuture<String> = CompletableFuture.supplyAsync({
        val rootPath = savedInitParams?.let { startupManager.getWorkspaceRoot(it) }
            ?: throw IllegalStateException("No workspace root found")

        indexExportService.exportIndex(params, rootPath)
    }, dispatcher.asExecutor())

    @JsonRequest("groovy/ast")
    fun getAst(params: AstParams): CompletableFuture<AstResult> = astRequestHandler.getAst(params)

    // Exposed for testing/CLI
    fun waitForDependencies(timeoutSeconds: Long = 60): Boolean = startupManager.waitForDependencies(timeoutSeconds)
}
