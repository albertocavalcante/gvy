package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.gradle.DependencyManager
import com.github.albertocavalcante.groovylsp.gradle.GradleConnectionPool
import com.github.albertocavalcante.groovylsp.gradle.GradleDependencyResolver
import com.github.albertocavalcante.groovylsp.progress.ProgressReporter
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import com.github.albertocavalcante.groovylsp.services.GroovyWorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
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

    // Async dependency management
    private val dependencyManager = DependencyManager(GradleDependencyResolver(), coroutineScope)
    private var savedInitParams: InitializeParams? = null
    private var savedInitOptionsMap: Map<String, Any>? = null

    // Service instances - initialized immediately to prevent UninitializedPropertyAccessException in tests
    private val textDocumentService = GroovyTextDocumentService(
        coroutineScope = coroutineScope,
        compilationService = compilationService,
        client = { client },
    )

    private val workspaceService = GroovyWorkspaceService(compilationService)

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

            // Diagnostics will be pushed
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

        compilationService.workspaceManager.initializeWorkspace(workspaceRoot)

        // Initialize Jenkins workspace with configuration from init options
        val config = ServerConfiguration.fromMap(savedInitOptionsMap)
        compilationService.workspaceManager.initializeJenkinsWorkspace(config)

        dependencyManager.startAsyncResolution(
            workspaceRoot = workspaceRoot,
            onProgress = { percentage, message ->
                progressReporter.updateProgress(message, percentage)
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

                // Notify client of successful resolution
                client?.showMessage(
                    MessageParams().apply {
                        type = MessageType.Info
                        message = "Dependencies loaded: ${resolution.dependencies.size} JARs from Gradle cache"
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
}
