package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
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
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class GroovyLanguageServer :
    LanguageServer,
    LanguageClientAware {

    private val logger = LoggerFactory.getLogger(GroovyLanguageServer::class.java)
    private var client: LanguageClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val compilationService = GroovyCompilationService()

    // Service instances - initialized immediately to prevent UninitializedPropertyAccessException in tests
    private val textDocumentService = GroovyTextDocumentService(
        coroutineScope,
        compilationService,
    ) { client }

    private val workspaceService = GroovyWorkspaceService()

    override fun connect(client: LanguageClient) {
        logger.info("Connected to language client")
        this.client = client
        // Services already initialized, client reference will be used when needed
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = coroutineScope.future {
        logger.info("Initializing Groovy Language Server...")
        logger.info("Client: ${params.clientInfo?.name ?: "Unknown"}")
        logger.info("Root URI: ${params.workspaceFolders?.firstOrNull()?.uri ?: "None"}")
        logger.info("Workspace folders: ${params.workspaceFolders?.map { it.uri }}")

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

            // Diagnostics will be pushed
        }

        val serverInfo = ServerInfo().apply {
            name = "Groovy Language Server"
            version = "0.1.0-SNAPSHOT"
        }

        InitializeResult(capabilities, serverInfo)
    }

    override fun initialized(params: InitializedParams) {
        logger.info("Server initialized")

        // Send a test message to the client
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Info
                message = "Groovy Language Server is ready!"
            },
        )
    }

    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.supplyAsync {
        logger.info("Shutting down Groovy Language Server...")
        try {
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

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService
}
