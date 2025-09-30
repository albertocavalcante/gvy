package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.codenarc.ConfigurationProvider
import com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.gradle.DependencyManager
import com.github.albertocavalcante.groovylsp.gradle.GradleConnectionPool
import com.github.albertocavalcante.groovylsp.gradle.SimpleDependencyResolver
import com.github.albertocavalcante.groovylsp.progress.ProgressReporter
import com.github.albertocavalcante.groovylsp.providers.semantictokens.SemanticTokenProvider
import com.github.albertocavalcante.groovylsp.repl.ReplCommandHandler
import com.github.albertocavalcante.groovylsp.repl.ReplSessionManager
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import com.github.albertocavalcante.groovylsp.services.GroovyWorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.ExecuteCommandOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
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

@Suppress("TooGenericExceptionCaught") // Main LSP server needs robust error handling
class GroovyLanguageServer :
    LanguageServer,
    LanguageClientAware,
    ConfigurationProvider {

    private val logger = LoggerFactory.getLogger(GroovyLanguageServer::class.java)
    private var client: LanguageClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val centralizedDependencyManager = CentralizedDependencyManager()
    private val compilationService = GroovyCompilationService(centralizedDependencyManager, this)

    // Configuration and workspace compilation
    private var configuration = ServerConfiguration()
    private var workspaceCompilationService: WorkspaceCompilationService? = null
    private var currentWorkspaceRoot: Path? = null

    // REPL functionality
    private var replSessionManager: ReplSessionManager? = null
    private var replCommandHandler: ReplCommandHandler? = null

    // Async dependency management
    private val dependencyManager = DependencyManager(SimpleDependencyResolver(), coroutineScope)
    private var savedInitParams: InitializeParams? = null

    // Service instances - initialized immediately to prevent UninitializedPropertyAccessException in tests
    private val textDocumentService = GroovyTextDocumentService(
        coroutineScope,
        compilationService,
        this,
        { client },
    )

    private val workspaceService = GroovyWorkspaceService(
        compilationService = compilationService,
        coroutineScope = coroutineScope,
        onConfigurationChanged = { newConfig -> handleConfigurationChange(newConfig) },
        replCommandHandler = null, // Will be set during initialization
    )

    override fun connect(client: LanguageClient) {
        logger.info("Connected to language client")
        this.client = client
        // Services already initialized, client reference will be used when needed
    }

    @Suppress("TooGenericExceptionCaught") // Entry point must handle all client initialization errors
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing Groovy Language Server...")
        logger.info("Client: ${params.clientInfo?.name ?: "Unknown"}")
        logger.info("Root URI: ${params.workspaceFolders?.firstOrNull()?.uri ?: "None"}")
        logger.info("Workspace folders: ${params.workspaceFolders?.map { it.uri }}")

        // Parse configuration from initialization options
        params.initializationOptions?.let { options ->
            val configMap = try {
                options as? Map<String, Any>
            } catch (e: Exception) {
                // Entry point for LSP initialization - must handle any client data safely
                logger.warn("Failed to parse initialization options as Map", e)
                null
            }
            configuration = ServerConfiguration.fromMap(configMap)
            logger.info("Parsed configuration: $configuration")
        }

        // Save params for later use in initialized()
        savedInitParams = params

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

            // References
            referencesProvider = Either.forLeft(true)

            // Type definition support
            typeDefinitionProvider = Either.forLeft(true)

            // Code actions support with proper options
            codeActionProvider = Either.forRight(
                CodeActionOptions().apply {
                    codeActionKinds = listOf(
                        CodeActionKind.QuickFix,
                        CodeActionKind.SourceFixAll,
                        CodeActionKind.SourceOrganizeImports,
                    )
                    resolveProvider = false // We provide complete actions immediately
                },
            )

            // Rename support
            renameProvider = Either.forLeft(true)

            // Execute command support (for REPL commands)
            executeCommandProvider = ExecuteCommandOptions().apply {
                commands = getSupportedCommands()
            }

            // Folding range support
            foldingRangeProvider = Either.forLeft(true)

            // Semantic tokens support
            semanticTokensProvider = SemanticTokensWithRegistrationOptions().apply {
                legend = SemanticTokenProvider.createLegend()
                full = Either.forLeft(true)
                range = Either.forLeft(false) // We don't support range requests yet
            }

            // Signature help support
            signatureHelpProvider = SignatureHelpOptions().apply {
                triggerCharacters = listOf("(", ",")
                retriggerCharacters = listOf(",", " ")
            }

            // Document formatting support
            documentFormattingProvider = Either.forLeft(true)

            // Range formatting support
            documentRangeFormattingProvider = Either.forLeft(true)

            // Diagnostics will be pushed
        }

        val serverInfo = ServerInfo().apply {
            name = "Groovy Language Server"
            version = Version.current
        }

        // NEW: Block until everything is ACTUALLY ready
        return coroutineScope.future {
            try {
                logger.info("Starting workspace initialization...")

                // Save params for later use
                savedInitParams = params

                // Get workspace root early
                val workspaceRoot = getWorkspaceRoot(params)

                if (workspaceRoot != null && configuration.shouldUseWorkspaceCompilation()) {
                    logger.info("Initializing workspace with dependencies first...")

                    // Step 1: Resolve dependencies FIRST (before compilation)
                    logger.info("Resolving dependencies...")
                    val dependencies = try {
                        val resolver = SimpleDependencyResolver()
                        resolver.resolveDependencies(workspaceRoot)
                    } catch (e: Exception) {
                        logger.warn("Failed to resolve dependencies, continuing without: ${e.message}")
                        emptyList()
                    }

                    logger.info("Found ${dependencies.size} dependencies")

                    // Step 2: Update centralized dependency manager with dependencies BEFORE workspace compilation
                    centralizedDependencyManager.updateDependencies(dependencies)

                    // Step 3: Initialize workspace compilation WITH dependencies
                    logger.info("Compiling workspace with dependencies...")
                    currentWorkspaceRoot = workspaceRoot
                    workspaceCompilationService =
                        WorkspaceCompilationService(coroutineScope, centralizedDependencyManager)
                    compilationService.enableWorkspaceMode(workspaceCompilationService!!)
                    workspaceService.setWorkspaceCompilationService(workspaceCompilationService)

                    // This is the critical fix: WAIT for workspace compilation to complete
                    val compilationResult = workspaceCompilationService!!.initializeWorkspace(workspaceRoot)

                    if (compilationResult.isSuccess) {
                        logger.info(
                            "Workspace compilation completed successfully: ${compilationResult.modulesByUri.size} modules",
                        )
                    } else {
                        logger.warn(
                            "Workspace compilation completed with errors: ${compilationResult.diagnostics.values.sumOf {
                                it.size
                            }} diagnostics",
                        )
                    }

                    // Initialize REPL if enabled (needs workspace compilation)
                    if (configuration.replEnabled) {
                        initializeReplFunctionality()
                    }
                } else {
                    logger.info("Using single-file compilation mode (no workspace or disabled)")
                }

                logger.info("LSP initialization complete - ALL features ready")
                InitializeResult(capabilities, serverInfo)
            } catch (e: Exception) {
                logger.error("Failed to initialize workspace", e)
                // Fall back to basic capabilities without workspace features
                logger.warn("Falling back to single-file mode due to initialization failure")
                InitializeResult(capabilities, serverInfo)
            }
        }
    }

    override fun initialized(params: InitializedParams) {
        // With the new implementation, everything is already done in initialize()
        logger.info("Server handshake completed - all features already ready")

        // Send ready message to client (initialization is already complete)
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Info
                message = "Groovy Language Server is fully ready! (Mode: ${configuration.compilationMode})"
            },
        )
    }

    /**
     * Initializes workspace compilation if enabled in configuration.
     */
    private fun initializeWorkspaceCompilation() {
        val initParams = savedInitParams ?: return
        val workspaceRoot = getWorkspaceRoot(initParams) ?: return

        // Store workspace root for ConfigurationProvider
        currentWorkspaceRoot = workspaceRoot

        if (configuration.shouldUseWorkspaceCompilation()) {
            logger.info("Initializing workspace compilation for: $workspaceRoot")

            workspaceCompilationService = WorkspaceCompilationService(coroutineScope, centralizedDependencyManager)
            compilationService.enableWorkspaceMode(workspaceCompilationService!!)
            workspaceService.setWorkspaceCompilationService(workspaceCompilationService)

            // Start initial workspace compilation in background
            coroutineScope.launch {
                try {
                    workspaceCompilationService?.initializeWorkspace(workspaceRoot)
                    logger.info("Workspace compilation initialized successfully")
                } catch (e: Exception) {
                    // Entry point handler - must gracefully handle all workspace initialization failures
                    logger.error("Failed to initialize workspace compilation", e)
                    // Fall back to single-file mode
                    workspaceCompilationService = null
                    compilationService.disableWorkspaceMode()
                    workspaceService.setWorkspaceCompilationService(null)
                }
            }
        } else {
            logger.info("Using single-file compilation mode")
        }
    }

    /**
     * Initializes REPL functionality with workspace integration.
     */
    @Suppress("TooGenericExceptionCaught") // Entry point must handle all REPL initialization errors
    private fun initializeReplFunctionality() {
        if (configuration.replEnabled) {
            logger.info("Initializing REPL functionality")

            try {
                // Initialize REPL session manager with workspace compilation service
                replSessionManager = ReplSessionManager(
                    workspaceService = workspaceCompilationService
                        ?: error("REPL requires workspace compilation"),
                    coroutineScope = coroutineScope,
                )

                // Initialize REPL command handler
                replCommandHandler = ReplCommandHandler(
                    sessionManager = replSessionManager!!,
                    coroutineScope = coroutineScope,
                )

                // Set the handler in the workspace service
                workspaceService.setReplCommandHandler(replCommandHandler)

                logger.info("REPL functionality initialized successfully")
            } catch (e: Exception) {
                logger.error("Failed to initialize REPL functionality", e)
                replSessionManager = null
                replCommandHandler = null
            }
        } else {
            logger.info("REPL functionality disabled")
        }
    }

    /**
     * Gets the list of supported commands for server capabilities.
     */
    private fun getSupportedCommands(): List<String> = workspaceService.getSupportedCommands()

    /**
     * Handles configuration changes from the client.
     */
    private fun handleConfigurationChange(newConfig: ServerConfiguration) {
        val oldMode = configuration.compilationMode
        val newMode = newConfig.compilationMode

        configuration = newConfig
        logger.info("Configuration updated: $configuration")

        // Handle compilation mode changes
        if (oldMode != newMode) {
            logger.info("Compilation mode changed from $oldMode to $newMode")

            when (newMode) {
                ServerConfiguration.CompilationMode.WORKSPACE -> {
                    if (workspaceCompilationService == null) {
                        // Switch to workspace mode
                        initializeWorkspaceCompilation()
                    }
                }
                ServerConfiguration.CompilationMode.SINGLE_FILE -> {
                    // Switch to single-file mode
                    workspaceCompilationService = null
                    compilationService.disableWorkspaceMode()
                    workspaceService.setWorkspaceCompilationService(null)
                    logger.info("Switched to single-file compilation mode")
                }
            }

            // Notify client about the mode change
            client?.showMessage(
                MessageParams().apply {
                    type = MessageType.Info
                    message = "Groovy LSP: Switched to ${newMode.name.lowercase().replace('_', '-')} compilation mode"
                },
            )
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

        val progressReporter = ProgressReporter(client)

        dependencyManager.startAsyncResolution(
            workspaceRoot = workspaceRoot,
            onProgress = { percentage, message ->
                progressReporter.updateProgress(message, percentage)
            },
            onComplete = { dependencies ->
                logger.info("Dependencies resolved: ${dependencies.size} JARs")

                // Update centralized dependency manager with resolved dependencies
                centralizedDependencyManager.updateDependencies(dependencies)

                progressReporter.complete("✅ Ready: ${dependencies.size} dependencies loaded")

                // Notify client of successful resolution
                client?.showMessage(
                    MessageParams().apply {
                        type = MessageType.Info
                        message = "Dependencies loaded: ${dependencies.size} JARs from Gradle cache"
                    },
                )
            },
            onError = { error ->
                logger.error("Failed to resolve dependencies", error)
                progressReporter.completeWithError("Failed to load dependencies: ${error.message}")

                // Still usable without external dependencies
                client?.showMessage(
                    MessageParams().apply {
                        type = MessageType.Warning
                        message = "Could not load Gradle dependencies - LSP will work with project files only"
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
            dependencyManager.cancel()

            // Shutdown REPL session manager
            replSessionManager?.shutdown()

            // Shutdown Gradle connection pool
            GradleConnectionPool.shutdown()

            coroutineScope.cancel()
        } catch (e: CancellationException) {
            logger.debug("Coroutine scope cancelled during shutdown", e)
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

    // ConfigurationProvider implementation
    override fun getServerConfiguration(): ServerConfiguration = configuration

    override fun getWorkspaceRoot(): Path? = currentWorkspaceRoot

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService
}
