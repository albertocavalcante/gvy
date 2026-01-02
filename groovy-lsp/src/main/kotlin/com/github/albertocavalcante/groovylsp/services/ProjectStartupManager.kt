package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.LogLevelConfigurator
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.engine.config.EngineConfiguration
import com.github.albertocavalcante.groovylsp.gradle.DependencyManager
import com.github.albertocavalcante.groovylsp.progress.ProgressReporter
import com.github.albertocavalcante.groovylsp.version.GroovyVersionInfo
import com.github.albertocavalcante.groovylsp.version.GroovyVersionResolver
import com.github.albertocavalcante.groovylsp.worker.WorkerFeature
import com.github.albertocavalcante.groovylsp.worker.WorkerRouter
import com.github.albertocavalcante.groovylsp.worker.WorkerRouterFactory
import com.github.albertocavalcante.groovylsp.worker.defaultWorkerDescriptors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.WatchKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

private const val PERCENTAGE_MULTIPLIER = 100
private const val POLLING_INTERVAL_MS = 100L
private const val MILLIS_PER_SECOND = 1000L
private const val STATUS_UPDATE_INTERVAL_MS = 100L

/**
 * Callback type for status updates.
 * Parameters: health, quiescent, message, filesIndexed, filesTotal
 */
typealias StatusUpdateCallback = (Health, Boolean, String?, Int?, Int?) -> Unit

/**
 * Manages project startup lifecycle: dependency resolution, file watching, and indexing.
 * De-clutters the main LanguageServer class.
 */
class ProjectStartupManager(
    private val compilationService: GroovyCompilationService,
    private val availableBuildTools: List<BuildTool>,
    private val coroutineScope: CoroutineScope,
    private val workerRouter: WorkerRouter = WorkerRouter(defaultWorkerDescriptors()),
    private val indexingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = LoggerFactory.getLogger(ProjectStartupManager::class.java)
    private val groovyVersionResolver = GroovyVersionResolver()

    var buildToolManager: BuildToolManager? = null
        private set

    var dependencyManager: DependencyManager? = null
        private set

    /**
     * Registers file watchers if the client supports it.
     */
    fun registerFileWatchers(client: LanguageClient?, capabilities: ClientCapabilities?) {
        val supportsDynamicRegistration = capabilities
            ?.workspace
            ?.didChangeWatchedFiles
            ?.dynamicRegistration == true

        if (!supportsDynamicRegistration) {
            logger.info("Client does not support dynamic file watcher registration - relying on client defaults")
            return
        }

        if (client == null) {
            logger.warn("No client connected - cannot register file watchers")
            return
        }

        val allWatchKinds = WatchKind.Create + WatchKind.Change + WatchKind.Delete
        val watchers = createFileSystemWatchers(allWatchKinds)
        val registrationOptions = DidChangeWatchedFilesRegistrationOptions(watchers)
        val registration = Registration(
            "groovy-lsp-file-watchers",
            "workspace/didChangeWatchedFiles",
            registrationOptions,
        )

        client.registerCapability(RegistrationParams(listOf(registration)))
            .thenAccept { logger.info("Successfully registered ${watchers.size} file watchers") }
            .exceptionally { error ->
                logger.warn("Failed to register file watchers: ${error.message}")
                null
            }
    }

    private fun createFileSystemWatchers(allWatchKinds: Int): List<FileSystemWatcher> = listOf(
        FileSystemWatcher(Either.forLeft("**/.codenarc"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/codenarc.xml"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/codenarc.groovy"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/codenarc.properties"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/*.gdsl"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/build.gradle"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/build.gradle.kts"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/settings.gradle"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/settings.gradle.kts"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/pom.xml"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/gradle.properties"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/*.groovy"), allWatchKinds),
        FileSystemWatcher(Either.forLeft("**/*.java"), allWatchKinds),
    )

    /**
     * Starts async dependency resolution and subsequent indexing.
     *
     * @param onStatusUpdate Callback for status notifications (follows rust-analyzer pattern)
     */
    @Suppress("TooGenericExceptionCaught")
    fun startAsyncDependencyResolution(
        client: LanguageClient?,
        initParams: InitializeParams?,
        initOptionsMap: Map<String, Any>?,
        textDocumentServiceRefresh: () -> Unit,
        onStatusUpdate: StatusUpdateCallback = { _, _, _, _, _ -> },
    ) {
        val config = ServerConfiguration.fromMap(initOptionsMap)

        // Apply log level from client settings
        LogLevelConfigurator.apply(config.logLevel)

        // Apply engine configuration
        val engineConfig = EngineConfiguration(type = config.parserEngine)
        compilationService.updateEngineConfiguration(engineConfig)

        if (initParams == null) {
            logger.warn("No saved initialization parameters - skipping dependency resolution")
            updateGroovyVersion(config, emptyList())
            onStatusUpdate(Health.Ok, true, "Ready (no workspace)", null, null)
            return
        }

        val workspaceRoot = getWorkspaceRoot(initParams) ?: run {
            logger.info("No workspace root found - running in light mode without dependencies")
            updateGroovyVersion(config, emptyList())
            onStatusUpdate(Health.Ok, true, "Ready (light mode)", null, null)
            return
        }

        logger.info("Starting background dependency resolution for: $workspaceRoot")
        logger.info(
            "Client connection status: ${if (client != null) "connected" else "NULL - notifications will not be sent"}",
        )

        // Send resolving deps status
        onStatusUpdate(Health.Ok, false, "Resolving build dependencies...", null, null)

        if (client != null) {
            logger.info("Sending 'Resolving build dependencies' notification to client")
            client.showMessage(
                MessageParams().apply {
                    type = MessageType.Info
                    message = "Resolving build dependencies..."
                },
            )
        } else {
            logger.warn("Cannot send showMessage - client is null")
        }

        val progressReporter = ProgressReporter(client)
        initializeWorkspaces(workspaceRoot, config)

        val dependencyManager = setupDependencyManager(config)

        dependencyManager.startAsyncResolution(
            workspaceRoot = workspaceRoot,
            onProgress = createProgressCallback(progressReporter, client, onStatusUpdate),
            onComplete = createCompletionCallback(
                workspaceRoot,
                config,
                textDocumentServiceRefresh,
                progressReporter,
                client,
                onStatusUpdate,
            ),
            onError = createErrorCallback(progressReporter, client, config, onStatusUpdate),
        )

        progressReporter.startDependencyResolution()
    }

    private fun initializeWorkspaces(workspaceRoot: Path, config: ServerConfiguration) {
        compilationService.workspaceManager.initializeWorkspace(workspaceRoot)

        // Setup Jenkins integration
        val jenkinsPluginManager = JenkinsPluginManager()
        val jenkinsMetadataService = JenkinsMetadataService(jenkinsPluginManager, config.jenkinsConfig)

        // Initialize Jenkins workspace with the plugin manager
        compilationService.workspaceManager.initializeJenkinsWorkspace(config, jenkinsPluginManager)

        // Asynchronously download and register plugins
        coroutineScope.launch(Dispatchers.IO) {
            try {
                logger.info("Starting Jenkins plugin metadata initialization")
                jenkinsMetadataService.initialize()
                logger.info("Jenkins plugin metadata initialization completed")
                // Note: We might want to trigger a classpath refresh here if critical plugins were added,
                // but JenkinsContext currently scans lazily or on demand. The jars are added to
                // potential classpath candidates, so next time buildClasspath is called (e.g. file open),
                // they will be picked up.
            } catch (e: Exception) {
                logger.error("Failed to initialize Jenkins plugin metadata", e)
            }
        }
    }

    private fun setupDependencyManager(config: ServerConfiguration): DependencyManager {
        logger.info("Gradle build strategy: ${config.gradleBuildStrategy}")

        val newBuildToolManager = BuildToolManager(
            buildTools = availableBuildTools,
            gradleBuildStrategy = config.gradleBuildStrategy,
        )
        buildToolManager = newBuildToolManager
        val newDependencyManager = DependencyManager(newBuildToolManager, coroutineScope)
        dependencyManager = newDependencyManager
        return newDependencyManager
    }

    private fun createProgressCallback(
        progressReporter: ProgressReporter,
        client: LanguageClient?,
        onStatusUpdate: StatusUpdateCallback,
    ): (Int, String) -> Unit = { percentage, message ->
        progressReporter.updateProgress(message, percentage)
        // Send status update for dependency resolution progress
        onStatusUpdate(Health.Ok, false, message, null, null)
        if (message.contains("Downloading Gradle distribution")) {
            client?.showMessage(
                MessageParams().apply {
                    type = MessageType.Info
                    this.message = message
                },
            )
        }
    }

    private fun createCompletionCallback(
        workspaceRoot: Path,
        config: ServerConfiguration,
        textDocumentServiceRefresh: () -> Unit,
        progressReporter: ProgressReporter,
        client: LanguageClient?,
        onStatusUpdate: StatusUpdateCallback,
    ): (WorkspaceResolution) -> Unit = { resolution ->
        logger.info(
            "Dependencies resolved: ${resolution.dependencies.size} JARs, " +
                "${resolution.sourceDirectories.size} source directories",
        )

        updateGroovyVersion(config, resolution.dependencies)

        compilationService.updateWorkspaceModel(
            workspaceRoot = workspaceRoot,
            dependencies = resolution.dependencies,
            sourceDirectories = resolution.sourceDirectories,
        )
        textDocumentServiceRefresh()

        progressReporter.complete("✅ Ready: ${resolution.dependencies.size} dependencies loaded")

        val toolName = dependencyManager?.getCurrentBuildToolName() ?: "Build Tool"
        if (client != null) {
            val msg = "Dependencies loaded: ${resolution.dependencies.size} JARs from $toolName"
            logger.info("Sending completion notification to client: $msg")
            client.showMessage(
                MessageParams().apply {
                    type = MessageType.Info
                    message = msg
                },
            )
        } else {
            logger.warn("Cannot send completion showMessage - client is null")
        }

        startWorkspaceIndexing(client, onStatusUpdate)
    }

    private fun createErrorCallback(
        progressReporter: ProgressReporter,
        client: LanguageClient?,
        config: ServerConfiguration,
        onStatusUpdate: StatusUpdateCallback,
    ): (Exception) -> Unit = { error ->
        logger.error("Failed to resolve dependencies", error)
        updateGroovyVersion(config, emptyList())
        progressReporter.completeWithError("Failed to load dependencies: ${error.message}")
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Warning
                message = "Could not load build dependencies - LSP will work with project files only"
            },
        )
        // Signal warning state but still quiescent (ready for requests, but degraded)
        onStatusUpdate(Health.Warning, true, "Dependencies failed: ${error.message}", null, null)
    }

    private fun updateGroovyVersion(config: ServerConfiguration, dependencies: List<Path>) {
        val info = groovyVersionResolver.resolve(dependencies, config.groovyLanguageVersion)
        compilationService.updateGroovyVersion(info)
        selectWorker(info, config)
    }

    internal fun selectWorker(
        info: GroovyVersionInfo,
        config: ServerConfiguration,
        requiredFeatures: Set<WorkerFeature> = emptySet(),
    ) {
        val router = resolveWorkerRouter(config)
        val selected = router.select(info, requiredFeatures)
        compilationService.updateSelectedWorker(selected)
    }

    private fun resolveWorkerRouter(config: ServerConfiguration): WorkerRouter {
        if (config.workerDescriptors.isEmpty()) {
            return workerRouter
        }
        return WorkerRouterFactory.fromConfig(config)
    }

    private fun startWorkspaceIndexing(client: LanguageClient?, onStatusUpdate: StatusUpdateCallback) {
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        if (sourceUris.isEmpty()) {
            logger.debug("No workspace sources to index")
            // No files to index, signal ready
            onStatusUpdate(Health.Ok, true, "Ready", null, null)
            return
        }

        val total = sourceUris.size
        logger.info("Starting workspace indexing: $total files")

        // Send initial indexing status with file counts
        onStatusUpdate(Health.Ok, false, "Indexing $total files...", 0, total)

        val indexingProgressReporter = ProgressReporter(client)
        indexingProgressReporter.startDependencyResolution(
            title = "Indexing workspace",
            initialMessage = "Indexing $total Groovy files...",
        )

        coroutineScope.launch(indexingDispatcher) {
            try {
                var lastStatusUpdate = System.currentTimeMillis()
                compilationService.indexAllWorkspaceSources(sourceUris) { indexed, totalFiles ->
                    val percentage = if (totalFiles > 0) (indexed * PERCENTAGE_MULTIPLIER / totalFiles) else 0
                    indexingProgressReporter.updateProgress("Indexed $indexed/$totalFiles files", percentage)
                    // Throttle status updates to avoid excessive notifications
                    val now = System.currentTimeMillis()
                    if (now - lastStatusUpdate >= STATUS_UPDATE_INTERVAL_MS || indexed == totalFiles) {
                        onStatusUpdate(Health.Ok, false, "Indexing $indexed/$totalFiles files", indexed, totalFiles)
                        lastStatusUpdate = now
                    }
                }
                indexingProgressReporter.complete("✅ Indexed $total files")
                logger.info("Workspace indexing complete: $total files")
                // Signal ready after indexing completes
                onStatusUpdate(Health.Ok, true, "Ready", total, total)
            } catch (e: Exception) {
                logger.error("Workspace indexing failed", e)
                indexingProgressReporter.completeWithError("Failed to index workspace: ${e.message}")
                // Signal warning state but still quiescent
                onStatusUpdate(Health.Warning, true, "Indexing failed: ${e.message}", null, null)
            }
        }
    }

    private fun getWorkspaceRoot(params: InitializeParams): Path? {
        val workspaceFolders = params.workspaceFolders
        if (!workspaceFolders.isNullOrEmpty()) {
            return parseUri(workspaceFolders.first().uri, "workspace folder URI")
        }

        @Suppress("DEPRECATION")
        val rootUri = params.rootUri

        @Suppress("DEPRECATION")
        val rootPath = params.rootPath

        return when {
            rootUri != null -> parseUri(rootUri, "root URI")
            rootPath != null -> parsePath(rootPath, "root path")
            else -> null
        }
    }

    private fun parseUri(uriString: String, description: String): Path? = try {
        Paths.get(URI.create(uriString))
    } catch (e: IllegalArgumentException) {
        logger.error("Invalid $description format: $uriString", e)
        null
    } catch (e: java.nio.file.FileSystemNotFoundException) {
        logger.error("File system not found for $description: $uriString", e)
        null
    }

    private fun parsePath(pathString: String, description: String): Path? = try {
        Paths.get(pathString)
    } catch (e: java.nio.file.InvalidPathException) {
        logger.error("Invalid $description: $pathString", e)
        null
    }

    @Suppress("ReturnCount")
    fun waitForDependencies(timeoutSeconds: Long = 60): Boolean {
        val start = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * MILLIS_PER_SECOND

        while (System.currentTimeMillis() - start < timeoutMs) {
            val manager = dependencyManager ?: return false

            if (manager.isDependenciesReady()) {
                return true
            }

            if (manager.getState() == DependencyManager.State.FAILED) {
                return false
            }

            if (sleepAndCheckInterruption()) {
                // Thread was interrupted
                return false
            }
        }
        return false
    }

    /**
     * Sleeps for the polling interval.
     * @return true if the thread was interrupted, false otherwise.
     */
    private fun sleepAndCheckInterruption(): Boolean {
        try {
            Thread.sleep(POLLING_INTERVAL_MS)
            return false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
    }

    fun shutdown() {
        dependencyManager?.cancel()
    }
}
