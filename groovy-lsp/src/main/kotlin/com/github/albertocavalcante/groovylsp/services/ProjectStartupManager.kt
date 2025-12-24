package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.gradle.DependencyManager
import com.github.albertocavalcante.groovylsp.progress.ProgressReporter
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

/**
 * Manages project startup lifecycle: dependency resolution, file watching, and indexing.
 * De-clutters the main LanguageServer class.
 */
class ProjectStartupManager(
    private val compilationService: GroovyCompilationService,
    private val availableBuildTools: List<BuildTool>,
    private val coroutineScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(ProjectStartupManager::class.java)

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
     */
    @Suppress("TooGenericExceptionCaught")
    fun startAsyncDependencyResolution(
        client: LanguageClient?,
        initParams: InitializeParams?,
        initOptionsMap: Map<String, Any>?,
        textDocumentServiceRefresh: () -> Unit,
        onReady: () -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        if (initParams == null) {
            logger.warn("No saved initialization parameters - skipping dependency resolution")
            onReady() // Still signal Ready to prevent clients from hanging
            return
        }

        val workspaceRoot = getWorkspaceRoot(initParams) ?: run {
            logger.info("No workspace root found - running in light mode without dependencies")
            onReady() // Still signal Ready in light mode
            return
        }

        logger.info("Starting background dependency resolution for: $workspaceRoot")
        logger.info(
            "Client connection status: ${if (client != null) "connected" else "NULL - notifications will not be sent"}",
        )
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
        initializeWorkspaces(workspaceRoot, initOptionsMap)

        val dependencyManager = setupDependencyManager(initOptionsMap)

        dependencyManager.startAsyncResolution(
            workspaceRoot = workspaceRoot,
            onProgress = createProgressCallback(progressReporter, client),
            onComplete = createCompletionCallback(
                workspaceRoot,
                textDocumentServiceRefresh,
                progressReporter,
                client,
                onReady,
            ),
            onError = createErrorCallback(progressReporter, client, onError),
        )

        progressReporter.startDependencyResolution()
    }

    private fun initializeWorkspaces(workspaceRoot: Path, initOptionsMap: Map<String, Any>?) {
        compilationService.workspaceManager.initializeWorkspace(workspaceRoot)
        val config = ServerConfiguration.fromMap(initOptionsMap)
        compilationService.workspaceManager.initializeJenkinsWorkspace(config)
    }

    private fun setupDependencyManager(initOptionsMap: Map<String, Any>?): DependencyManager {
        val config = ServerConfiguration.fromMap(initOptionsMap)
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
    ): (Int, String) -> Unit = { percentage, message ->
        progressReporter.updateProgress(message, percentage)
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
        textDocumentServiceRefresh: () -> Unit,
        progressReporter: ProgressReporter,
        client: LanguageClient?,
        onReady: () -> Unit,
    ): (WorkspaceResolution) -> Unit = { resolution ->
        logger.info(
            "Dependencies resolved: ${resolution.dependencies.size} JARs, " +
                "${resolution.sourceDirectories.size} source directories",
        )

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

        startWorkspaceIndexing(client)

        // Signal that server is ready after dependency resolution and indexing starts
        onReady()
    }

    private fun createErrorCallback(
        progressReporter: ProgressReporter,
        client: LanguageClient?,
        onError: (Exception) -> Unit,
    ): (Exception) -> Unit = { error ->
        logger.error("Failed to resolve dependencies", error)
        progressReporter.completeWithError("Failed to load dependencies: ${error.message}")
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Warning
                message = "Could not load build dependencies - LSP will work with project files only"
            },
        )
        onError(error)
    }

    private fun startWorkspaceIndexing(client: LanguageClient?) {
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        if (sourceUris.isEmpty()) {
            logger.debug("No workspace sources to index")
            return
        }

        logger.info("Starting workspace indexing: ${sourceUris.size} files")

        val indexingProgressReporter = ProgressReporter(client)
        indexingProgressReporter.startDependencyResolution(
            title = "Indexing workspace",
            initialMessage = "Indexing ${sourceUris.size} Groovy files...",
        )

        coroutineScope.launch(Dispatchers.Default) {
            try {
                compilationService.indexAllWorkspaceSources(sourceUris) { indexed, total ->
                    val percentage = if (total > 0) (indexed * PERCENTAGE_MULTIPLIER / total) else 0
                    indexingProgressReporter.updateProgress("Indexed $indexed/$total files", percentage)
                }
                indexingProgressReporter.complete("✅ Indexed ${sourceUris.size} files")
                logger.info("Workspace indexing complete: ${sourceUris.size} files")
            } catch (e: Exception) {
                logger.error("Workspace indexing failed", e)
                indexingProgressReporter.completeWithError("Failed to index workspace: ${e.message}")
            }
        }
    }

    private fun getWorkspaceRoot(params: InitializeParams): Path? {
        val workspaceFolders = params.workspaceFolders
        if (!workspaceFolders.isNullOrEmpty()) {
            return parseUri(workspaceFolders.first().uri, "workspace folder URI")
        }

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
            val manager = dependencyManager
            if (manager == null) {
                // Stop if no manager, which is an error state.
                return false
            }

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
