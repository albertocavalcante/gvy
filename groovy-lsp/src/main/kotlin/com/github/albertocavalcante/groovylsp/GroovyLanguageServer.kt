package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.TestCommand
import com.github.albertocavalcante.groovylsp.buildtool.bsp.BspBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleConnectionPool
import com.github.albertocavalcante.groovylsp.buildtool.maven.MavenBuildTool
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.gradle.DependencyManager
import com.github.albertocavalcante.groovylsp.progress.ProgressReporter
import com.github.albertocavalcante.groovylsp.providers.testing.RunTestParams
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import com.github.albertocavalcante.groovylsp.services.GroovyWorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeLensOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.WatchKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val GRADLE_POOL_SHUTDOWN_TIMEOUT_SECONDS = 15L

class GroovyLanguageServer :
    LanguageServer,
    LanguageClientAware {

    private val logger = LoggerFactory.getLogger(GroovyLanguageServer::class.java)
    private var client: LanguageClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val compilationService = GroovyCompilationService()

    // Available build tools in detection priority order
    // BSP comes first - it's opt-in (only used if .bsp/ directory exists)
    // This enables support for Bazel, sbt, Mill without direct implementations
    private val availableBuildTools: List<BuildTool> = listOf(
        BspBuildTool(),
        GradleBuildTool(),
        MavenBuildTool(),
    )

    // Async dependency management - lazily initialized after config parsing
    private var buildToolManager: BuildToolManager? = null
    private var dependencyManager: DependencyManager? = null
    private var savedInitParams: InitializeParams? = null
    private var savedInitOptionsMap: Map<String, Any>? = null
    private var clientCapabilities: ClientCapabilities? = null

    // Service instances - initialized immediately to prevent UninitializedPropertyAccessException in tests
    private val textDocumentService = GroovyTextDocumentService(
        coroutineScope = coroutineScope,
        compilationService = compilationService,
        client = { client },
    )

    private val workspaceService = GroovyWorkspaceService(compilationService, coroutineScope, textDocumentService)

    override fun connect(client: LanguageClient) {
        logger.info("Connected to language client")
        this.client = client
        // Services already initialized, client reference will be used when needed
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing Groovy Language Server...")
        logger.info("Client: ${params.clientInfo?.name ?: "Unknown"}")
        logger.info("Root URI: ${params.workspaceFolders?.firstOrNull()?.uri ?: "None"}")
        logger.info("Workspace folders: ${params.workspaceFolders?.map { it.uri }}")

        // Save params for later use in initialized()
        savedInitParams = params
        clientCapabilities = params.capabilities

        // Parse initialization options for Jenkins configuration
        val initOptions = params.initializationOptions
        @Suppress("UNCHECKED_CAST")
        savedInitOptionsMap = when (initOptions) {
            is Map<*, *> -> initOptions as? Map<String, Any>
            else -> null
        }

        val capabilities = ServerCapabilities().apply {
            // Text synchronization
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

            // Completion support
            completionProvider = CompletionOptions().apply {
                resolveProvider = false
                triggerCharacters = listOf(".", ":", "=", "*")
            }

            // Hover support
            hoverProvider = Either.forLeft(true)

            // Definition support
            definitionProvider = Either.forLeft(true)

            // Document symbols
            documentSymbolProvider = Either.forLeft(true)

            // Workspace symbols
            workspaceSymbolProvider = Either.forLeft(true)

            // Document formatting
            documentFormattingProvider = Either.forLeft(true)

            // References
            referencesProvider = Either.forLeft(true)

            // Type definition support
            typeDefinitionProvider = Either.forLeft(true)

            // Signature help support
            signatureHelpProvider = SignatureHelpOptions().apply {
                triggerCharacters = listOf("(", ",")
            }

            // Rename support
            renameProvider = Either.forLeft(true)

            // Code actions
            codeActionProvider = Either.forLeft(true)

            // Semantic tokens support
            semanticTokensProvider = org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions().apply {
                legend = org.eclipse.lsp4j.SemanticTokensLegend().apply {
                    // Token types - MUST match indices in JenkinsSemanticTokenProvider.TokenTypes
                    tokenTypes = listOf(
                        org.eclipse.lsp4j.SemanticTokenTypes.Namespace, // 0
                        org.eclipse.lsp4j.SemanticTokenTypes.Type, // 1
                        org.eclipse.lsp4j.SemanticTokenTypes.Class, // 2
                        org.eclipse.lsp4j.SemanticTokenTypes.Enum, // 3
                        org.eclipse.lsp4j.SemanticTokenTypes.Interface, // 4
                        org.eclipse.lsp4j.SemanticTokenTypes.Struct, // 5
                        org.eclipse.lsp4j.SemanticTokenTypes.TypeParameter, // 6
                        org.eclipse.lsp4j.SemanticTokenTypes.Parameter, // 7
                        org.eclipse.lsp4j.SemanticTokenTypes.Variable, // 8
                        org.eclipse.lsp4j.SemanticTokenTypes.Property, // 9
                        org.eclipse.lsp4j.SemanticTokenTypes.EnumMember, // 10
                        org.eclipse.lsp4j.SemanticTokenTypes.Event, // 11
                        org.eclipse.lsp4j.SemanticTokenTypes.Function, // 12
                        org.eclipse.lsp4j.SemanticTokenTypes.Method, // 13
                        org.eclipse.lsp4j.SemanticTokenTypes.Macro, // 14 <- Used for pipeline blocks
                        org.eclipse.lsp4j.SemanticTokenTypes.Keyword, // 15
                        org.eclipse.lsp4j.SemanticTokenTypes.Modifier, // 16
                        org.eclipse.lsp4j.SemanticTokenTypes.Comment, // 17
                        org.eclipse.lsp4j.SemanticTokenTypes.String, // 18
                        org.eclipse.lsp4j.SemanticTokenTypes.Number, // 19
                        org.eclipse.lsp4j.SemanticTokenTypes.Regexp, // 20
                        org.eclipse.lsp4j.SemanticTokenTypes.Operator, // 21
                        org.eclipse.lsp4j.SemanticTokenTypes.Decorator, // 22 <- Used for wrapper blocks
                    )

                    // Token modifiers (bitfield)
                    tokenModifiers = listOf(
                        org.eclipse.lsp4j.SemanticTokenModifiers.Declaration,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Definition,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Readonly,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Static,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Deprecated,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Abstract,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Async,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Modification,
                        org.eclipse.lsp4j.SemanticTokenModifiers.Documentation,
                        org.eclipse.lsp4j.SemanticTokenModifiers.DefaultLibrary,
                    )
                }

                // Support full document semantic tokens (no delta updates yet)
                full = Either.forLeft(true)

                // TODO: Add range support for better performance with large files
                // range = Either.forLeft(true)
            }

            // CodeLens support for test run/debug buttons
            codeLensProvider = CodeLensOptions().apply {
                resolveProvider = false
            }

            // Diagnostics will be pushed

            // NOTE: File watching requires dynamic registration in initialized() because
            // the server needs to tell the client exactly which files to watch.
            // See registerFileWatchers() for the actual registration.
        }

        val serverInfo = ServerInfo().apply {
            name = "Groovy Language Server"
            version = Version.current
        }

        // Return immediately - NO BLOCKING dependency resolution!
        logger.info("LSP initialized - ready for requests")
        return CompletableFuture.completedFuture(InitializeResult(capabilities, serverInfo))
    }

    override fun initialized(params: InitializedParams) {
        logger.info("Server initialized - starting async dependency resolution")

        // Register file watchers for config files (if client supports dynamic registration)
        registerFileWatchers()

        // Send ready message to client
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Info
                message = "Groovy Language Server is ready!"
            },
        )

        // Start async dependency resolution in background
        startAsyncDependencyResolution()
    }

    /**
     * Registers file system watchers for configuration files.
     * Uses LSP dynamic registration to tell the client exactly which files to watch.
     *
     * Watched files:
     * - .codenarc, codenarc.xml, codenarc.groovy - CodeNarc rule configuration
     * - *.gdsl - Groovy DSL descriptors (especially for Jenkins)
     * - build.gradle, build.gradle.kts, pom.xml - Build files for dependency changes
     */
    private fun registerFileWatchers() {
        val supportsDynamicRegistration = clientCapabilities
            ?.workspace
            ?.didChangeWatchedFiles
            ?.dynamicRegistration == true

        if (!supportsDynamicRegistration) {
            logger.info("Client does not support dynamic file watcher registration - relying on client defaults")
            return
        }

        val currentClient = client
        if (currentClient == null) {
            logger.warn("No client connected - cannot register file watchers")
            return
        }

        // Define file watchers with specific patterns and watch kinds
        // WatchKind flags: Create = 1, Change = 2, Delete = 4 (can be combined)
        val allWatchKinds = WatchKind.Create + WatchKind.Change + WatchKind.Delete

        val watchers = listOf(
            // CodeNarc configuration files
            FileSystemWatcher(Either.forLeft("**/.codenarc"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/codenarc.xml"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/codenarc.groovy"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/codenarc.properties"), allWatchKinds),

            // GDSL files for DSL support (Jenkins, Gradle, etc.)
            FileSystemWatcher(Either.forLeft("**/*.gdsl"), allWatchKinds),

            // Build files for dependency resolution
            FileSystemWatcher(Either.forLeft("**/build.gradle"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/build.gradle.kts"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/settings.gradle"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/settings.gradle.kts"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/pom.xml"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/gradle.properties"), allWatchKinds),

            // Groovy source files for incremental indexing
            FileSystemWatcher(Either.forLeft("**/*.groovy"), allWatchKinds),
            FileSystemWatcher(Either.forLeft("**/*.java"), allWatchKinds),
        )

        val registrationOptions = DidChangeWatchedFilesRegistrationOptions(watchers)

        val registration = Registration(
            "groovy-lsp-file-watchers",
            "workspace/didChangeWatchedFiles",
            registrationOptions,
        )

        currentClient.registerCapability(RegistrationParams(listOf(registration)))
            .thenAccept {
                logger.info("Successfully registered ${watchers.size} file watchers")
            }
            .exceptionally { error ->
                logger.warn("Failed to register file watchers: ${error.message}")
                null
            }
    }

    /**
     * Starts asynchronous dependency resolution without blocking the LSP.
     */
    private fun startAsyncDependencyResolution() {
        val initParams = savedInitParams
        if (initParams == null) {
            logger.warn("No saved initialization parameters - skipping dependency resolution")
            return
        }

        val workspaceRoot = getWorkspaceRoot(initParams)
        if (workspaceRoot == null) {
            logger.info("No workspace root found - running in light mode without dependencies")
            return
        }

        logger.info("Starting background dependency resolution for: $workspaceRoot")
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Info
                message = "Resolving build dependencies..."
            },
        )

        val progressReporter = ProgressReporter(client)

        compilationService.workspaceManager.initializeWorkspace(workspaceRoot)

        // Initialize Jenkins workspace with configuration from init options
        val config = ServerConfiguration.fromMap(savedInitOptionsMap)
        compilationService.workspaceManager.initializeJenkinsWorkspace(config)

        // Initialize build tool manager with configured strategy
        // This allows users to control BSP vs native Gradle resolution
        logger.info("Gradle build strategy: ${config.gradleBuildStrategy}")
        val newBuildToolManager = BuildToolManager(
            buildTools = availableBuildTools,
            gradleBuildStrategy = config.gradleBuildStrategy,
        )
        buildToolManager = newBuildToolManager
        val newDependencyManager = DependencyManager(newBuildToolManager, coroutineScope)
        dependencyManager = newDependencyManager

        newDependencyManager.startAsyncResolution(
            workspaceRoot = workspaceRoot,
            onProgress = { percentage, message ->
                progressReporter.updateProgress(message, percentage)

                // Send window/showMessage for Gradle distribution download (cold start scenario)
                // This ensures e2e tests get the notification they're waiting for
                // Also applicable if Maven downloads internet...
                if (message.contains("Downloading Gradle distribution")) {
                    client?.showMessage(
                        MessageParams().apply {
                            type = MessageType.Info
                            this.message = message
                        },
                    )
                }
            },
            onComplete = { resolution ->
                logger.info(
                    "Dependencies resolved: ${resolution.dependencies.size} JARs, " +
                        "${resolution.sourceDirectories.size} source directories",
                )

                // Update compilation service with resolved dependencies
                compilationService.updateWorkspaceModel(
                    workspaceRoot = workspaceRoot,
                    dependencies = resolution.dependencies,
                    sourceDirectories = resolution.sourceDirectories,
                )
                textDocumentService.refreshOpenDocuments()

                progressReporter.complete(
                    "✅ Ready: ${resolution.dependencies.size} dependencies loaded",
                )

                // Get tool name for friendly message
                val toolName = dependencyManager?.getCurrentBuildToolName() ?: "Build Tool"

                // Notify client of successful resolution
                client?.showMessage(
                    MessageParams().apply {
                        type = MessageType.Info
                        message = "Dependencies loaded: ${resolution.dependencies.size} JARs from $toolName"
                    },
                )

                // Start workspace indexing after dependencies are resolved
                startWorkspaceIndexing(workspaceRoot, progressReporter)
            },
            onError = { error ->
                logger.error("Failed to resolve dependencies", error)
                progressReporter.completeWithError("Failed to load dependencies: ${error.message}")

                // Still usable without external dependencies
                client?.showMessage(
                    MessageParams().apply {
                        type = MessageType.Warning
                        message = "Could not load build dependencies - LSP will work with project files only"
                    },
                )
            },
        )

        // Start progress reporting
        progressReporter.startDependencyResolution()
    }

    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.supplyAsync {
        logger.info("Shutting down Groovy Language Server...")
        try {
            // Cancel dependency resolution if in progress
            dependencyManager?.cancel()

            // Shutdown Gradle connection pool
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
        // LSP spec says exit should just terminate - the client handles process termination
        // We don't call System.exit() here as it should be handled by the caller
    }

    /**
     * Extracts the workspace root path from initialization parameters.
     * Prefers workspaceFolders over deprecated rootUri/rootPath.
     */
    private fun getWorkspaceRoot(params: InitializeParams): Path? {
        // Try workspaceFolders first (LSP 3.6+)
        val workspaceFolders = params.workspaceFolders
        if (!workspaceFolders.isNullOrEmpty()) {
            return parseUri(workspaceFolders.first().uri, "workspace folder URI")
        }

        // Fallback to rootUri (LSP 3.0+) or deprecated rootPath
        val rootUri = params.rootUri

        @Suppress("DEPRECATION")
        val rootPath = params.rootPath

        return when {
            rootUri != null -> parseUri(rootUri, "root URI")
            rootPath != null -> parsePath(rootPath, "root path")
            else -> null
        }
    }

    /**
     * Parses a URI string to a Path, handling exceptions gracefully.
     */
    private fun parseUri(uriString: String, description: String): Path? = try {
        Paths.get(URI.create(uriString))
    } catch (e: IllegalArgumentException) {
        logger.error("Invalid $description format: $uriString", e)
        null
    } catch (e: java.nio.file.FileSystemNotFoundException) {
        logger.error("File system not found for $description: $uriString", e)
        null
    }

    /**
     * Parses a path string to a Path, handling exceptions gracefully.
     */
    private fun parsePath(pathString: String, description: String): Path? = try {
        Paths.get(pathString)
    } catch (e: java.nio.file.InvalidPathException) {
        logger.error("Invalid $description: $pathString", e)
        null
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    /**
     * Starts workspace indexing in the background after dependencies are resolved.
     */
    @Suppress("UNUSED_PARAMETER") // Parameters kept for future use (e.g., per-workspace progress)
    private fun startWorkspaceIndexing(workspaceRoot: Path, progressReporter: ProgressReporter) {
        val sourceUris = compilationService.workspaceManager.getWorkspaceSourceUris()
        if (sourceUris.isEmpty()) {
            logger.debug("No workspace sources to index")
            return
        }

        logger.info("Starting workspace indexing: ${sourceUris.size} files")

        // Create a new progress reporter for indexing
        val indexingProgressReporter = ProgressReporter(client)
        indexingProgressReporter.startDependencyResolution(
            title = "Indexing workspace",
            initialMessage = "Indexing ${sourceUris.size} Groovy files...",
        )

        coroutineScope.launch(Dispatchers.Default) {
            try {
                compilationService.indexAllWorkspaceSources(sourceUris) { indexed, total ->
                    val percentage = if (total > 0) (indexed * 100 / total) else 0
                    indexingProgressReporter.updateProgress(
                        "Indexed $indexed/$total files",
                        percentage,
                    )
                }

                indexingProgressReporter.complete("✅ Indexed ${sourceUris.size} files")
                logger.info("Workspace indexing complete: ${sourceUris.size} files")
            } catch (e: Exception) {
                logger.error("Workspace indexing failed", e)
                indexingProgressReporter.completeWithError("Failed to index workspace: ${e.message}")
            }
        }
    }

    /**
     * Waits for dependency resolution to complete, useful for CLI/testing.
     */
    fun waitForDependencies(timeoutSeconds: Long = 60): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutSeconds * 1000) {
            val manager = dependencyManager ?: return false
            if (manager.isDependenciesReady()) {
                return true
            }
            if (manager.getState() ==
                com.github.albertocavalcante.groovylsp.gradle.DependencyManager.State.FAILED
            ) {
                return false
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    // ============================================================================
    // CUSTOM LSP METHODS
    // ============================================================================

    /**
     * Discover all Spock test classes and feature methods in the workspace.
     *
     * Custom LSP request: `groovy/discoverTests`
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("groovy/discoverTests")
    fun discoverTests(
        params: com.github.albertocavalcante.groovylsp.providers.testing.DiscoverTestsParams,
    ): CompletableFuture<List<com.github.albertocavalcante.groovylsp.providers.testing.TestSuite>> {
        logger.info("Received groovy/discoverTests request for: ${params.workspaceUri}")

        return CompletableFuture.supplyAsync {
            val provider = com.github.albertocavalcante.groovylsp.providers.testing.TestDiscoveryProvider(
                compilationService,
            )
            provider.discoverTests(params.workspaceUri)
        }
    }

    /**
     * Generate a test execution command for the given test suite/method.
     *
     * Custom LSP request: `groovy/runTest`
     */
    @Suppress("TooGenericExceptionCaught")
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("groovy/runTest")
    fun runTest(params: RunTestParams): CompletableFuture<TestCommand> {
        logger.info("Received groovy/runTest request for suite: ${params.suite}, test: ${params.test}")

        return CompletableFuture.supplyAsync {
            try {
                val workspaceRoot = compilationService.workspaceManager.getWorkspaceRoot()
                    ?: throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams,
                            "No workspace root found",
                            null,
                        ),
                    )

                val buildTool = buildToolManager?.detectBuildTool(workspaceRoot)
                    ?: throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                            "No build tool detected for workspace: $workspaceRoot",
                            null,
                        ),
                    )

                buildTool.getTestCommand(
                    workspaceRoot = workspaceRoot,
                    suite = params.suite,
                    test = params.test,
                    debug = params.debug,
                )
            } catch (e: Exception) {
                logger.error("Error generating test command", e)
                throw if (e is org.eclipse.lsp4j.jsonrpc.ResponseErrorException) {
                    e
                } else {
                    org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                            e.message ?: "Internal error generating test command",
                            null,
                        ),
                    )
                }
            }
        }
    }
}
