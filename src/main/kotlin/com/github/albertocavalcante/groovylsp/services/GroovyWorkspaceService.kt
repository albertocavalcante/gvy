package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.providers.symbols.WorkspaceSymbolProvider
import com.github.albertocavalcante.groovylsp.repl.ReplCommandHandler
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Handles all workspace related LSP operations.
 */
@Suppress("TooGenericExceptionCaught") // LSP entry point needs robust error handling
class GroovyWorkspaceService(
    private val compilationService: GroovyCompilationService,
    private val coroutineScope: CoroutineScope,
    private val onConfigurationChanged: (ServerConfiguration) -> Unit = {},
    private var replCommandHandler: ReplCommandHandler? = null,
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(GroovyWorkspaceService::class.java)

    // Workspace compilation service (set by language server)
    private var workspaceCompilationService: WorkspaceCompilationService? = null

    // Workspace symbol provider - created lazily
    private val workspaceSymbolProvider by lazy {
        WorkspaceSymbolProvider(compilationService, workspaceCompilationService, coroutineScope)
    }

    /**
     * Sets the REPL command handler after initialization.
     */
    fun setReplCommandHandler(handler: ReplCommandHandler?) {
        this.replCommandHandler = handler
    }

    /**
     * Sets the workspace compilation service after initialization.
     */
    fun setWorkspaceCompilationService(service: WorkspaceCompilationService?) {
        this.workspaceCompilationService = service
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.info("Configuration changed: ${params.settings}")

        val configMap = try {
            params.settings as? Map<String, Any>
        } catch (e: Exception) {
            logger.warn("Failed to parse configuration settings as Map", e)
            null
        }

        val newConfig = ServerConfiguration.fromMap(configMap)
        logger.info("Parsed new configuration: $newConfig")

        // Notify the language server about configuration changes
        onConfigurationChanged(newConfig)
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.debug("Watched files changed: ${params.changes?.size ?: 0} changes")
        params.changes?.forEach { change -> processFileChange(change) }
    }

    private fun processFileChange(change: org.eclipse.lsp4j.FileEvent) {
        try {
            val uri = URI.create(change.uri)
            if (!uri.toString().endsWith(".groovy")) return

            when (change.type) {
                org.eclipse.lsp4j.FileChangeType.Created,
                org.eclipse.lsp4j.FileChangeType.Changed,
                -> {
                    workspaceSymbolProvider.updateFileSymbols(uri)
                }
                org.eclipse.lsp4j.FileChangeType.Deleted -> {
                    workspaceSymbolProvider.removeFileSymbols(uri)
                }
            }
        } catch (e: Exception) {
            logger.warn("Error processing file change: ${change.uri}", e)
        }
    }

    override fun symbol(
        params: WorkspaceSymbolParams,
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        logger.debug("Workspace symbols requested for query: '${params.query}'")

        return try {
            val symbols = workspaceSymbolProvider.searchSymbols(params.query)
            logger.debug("Returning ${symbols.size} workspace symbols for query '${params.query}'")
            CompletableFuture.completedFuture(
                Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(
                    symbols.mapNotNull { either ->
                        if (either.isLeft) either.left else null
                    },
                ),
            )
        } catch (e: Exception) {
            logger.error("Error providing workspace symbols for query '${params.query}'", e)
            CompletableFuture.completedFuture(
                Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(emptyList()),
            )
        }
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any?> {
        logger.debug("Executing command: ${params.command} with ${params.arguments?.size ?: 0} arguments")

        // Check if this is a REPL command
        val handler = replCommandHandler
        if (handler != null && isReplCommand(params.command)) {
            return handler.executeCommand(params.command, params.arguments ?: emptyList())
        }

        // Handle other workspace commands here in the future
        logger.warn("Unknown command: ${params.command}")
        return CompletableFuture.completedFuture(null)
    }

    /**
     * Checks if a command is a REPL command.
     */
    private fun isReplCommand(command: String): Boolean = command.startsWith("groovy/repl/")

    /**
     * Gets the list of supported commands for server capabilities.
     */
    fun getSupportedCommands(): List<String> {
        val commands = mutableListOf<String>()

        // Add REPL commands if handler is available
        val handler = replCommandHandler
        if (handler != null) {
            commands.addAll(handler.getSupportedCommands())
        }

        return commands
    }
}
