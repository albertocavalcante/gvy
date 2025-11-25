package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.Version
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Handles all workspace related LSP operations.
 */
class GroovyWorkspaceService(private val compilationService: GroovyCompilationService) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(GroovyWorkspaceService::class.java)

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        logger.info("Executing command: ${params.command}")
        return when (params.command) {
            "groovy.version" -> CompletableFuture.completedFuture(Version.current)
            else -> {
                logger.warn("Unknown command: ${params.command}")
                CompletableFuture.completedFuture(null)
            }
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.info("Configuration changed, updating Jenkins context if applicable")

        // Parse new configuration
        val settings = params.settings

        @Suppress("UNCHECKED_CAST")
        val settingsMap = when (settings) {
            is Map<*, *> -> settings as? Map<String, Any>
            else -> null
        }

        if (settingsMap != null) {
            val newConfig = ServerConfiguration.fromMap(settingsMap)

            // Update Jenkins workspace configuration
            compilationService.workspaceManager.updateJenkinsConfiguration(newConfig)
            logger.info("Jenkins configuration updated")
        }
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.debug("Watched files changed: ${params.changes?.size ?: 0} changes")

        val shouldReloadGdsl = params.changes.any { change ->
            try {
                val uri = java.net.URI.create(change.uri)
                compilationService.workspaceManager.isGdslFile(uri)
            } catch (e: Exception) {
                false
            }
        }

        if (shouldReloadGdsl) {
            logger.info("GDSL file changed, reloading metadata")
            compilationService.workspaceManager.reloadJenkinsGdsl()
        }

        // Could trigger re-compilation of affected files
    }

    override fun symbol(
        params: WorkspaceSymbolParams,
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val query = params.query
        if (query.isNullOrBlank()) {
            logger.debug("Workspace symbol query blank; returning empty result")
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        val storages = compilationService.getAllSymbolStorages()
        val results = storages.flatMap { (uri, storage) ->
            val symbols: List<Symbol> = storage.findMatching(uri, query)

            symbols.mapNotNull { it.toSymbolInformation() }
        }

        return CompletableFuture.completedFuture(Either.forLeft(results))
    }
}
