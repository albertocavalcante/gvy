package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

/** An actual JSON-RPC service with no production caches, threads or Gradle connections. */
internal class SessionTestServer :
    LanguageServer,
    LanguageClientAware {
    var shutdownCalls = 0
    var connectCalls = 0
    var exitCalls = 0
    var connectFailure: IllegalStateException? = null
    var onExit: ((Int) -> Unit)? = null
    var shutdownResult: CompletableFuture<Any> = CompletableFuture.completedFuture(Unit)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
        CompletableFuture.completedFuture(InitializeResult(ServerCapabilities()))

    override fun shutdown(): CompletableFuture<Any> {
        shutdownCalls++
        return shutdownResult
    }

    override fun exit() {
        exitCalls++
        onExit?.invoke(if (shutdownCalls > 0) 0 else 1)
    }

    override fun getTextDocumentService(): TextDocumentService = object : TextDocumentService {
        override fun didOpen(params: DidOpenTextDocumentParams) = Unit
        override fun didChange(params: DidChangeTextDocumentParams) = Unit
        override fun didClose(params: DidCloseTextDocumentParams) = Unit
        override fun didSave(params: DidSaveTextDocumentParams) = Unit
    }

    override fun getWorkspaceService(): WorkspaceService = object : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit
        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
    }

    override fun connect(client: LanguageClient) {
        connectCalls++
        connectFailure?.let { throw it }
    }
}
