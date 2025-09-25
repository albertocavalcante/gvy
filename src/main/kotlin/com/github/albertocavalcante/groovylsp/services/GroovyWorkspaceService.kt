package com.github.albertocavalcante.groovylsp.services

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
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
class GroovyWorkspaceService : WorkspaceService {

    private val logger = LoggerFactory.getLogger(GroovyWorkspaceService::class.java)

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.debug("Configuration changed")
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.debug("Watched files changed: ${params.changes?.size ?: 0} changes")
        // Could trigger re-compilation of affected files
    }

    override fun symbol(
        params: WorkspaceSymbolParams,
    ): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> = CompletableFuture.completedFuture(
        Either.forLeft(emptyList()),
    )
}
