package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.dsl.GroovyCompletions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Handles all text document related LSP operations.
 */
class GroovyTextDocumentService(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    private val client: () -> LanguageClient?,
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(GroovyTextDocumentService::class.java)

    /**
     * Helper function to publish diagnostics with better readability
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        client()?.publishDiagnostics(
            PublishDiagnosticsParams().apply {
                this.uri = uri
                this.diagnostics = diagnostics
            },
        )
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        logger.info("Document opened: ${params.textDocument.uri}")

        // Compile the document and publish diagnostics
        coroutineScope.launch {
            try {
                val uri = java.net.URI.create(params.textDocument.uri)
                val result = compilationService.compile(uri, params.textDocument.text)

                publishDiagnostics(params.textDocument.uri, result.diagnostics)

                logger.debug("Published ${result.diagnostics.size} diagnostics for ${params.textDocument.uri}")
            } catch (e: org.codehaus.groovy.control.CompilationFailedException) {
                logger.error("Compilation failed on file open: ${params.textDocument.uri}", e)
            }
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        logger.debug("Document changed: ${params.textDocument.uri}")

        // For full sync, we get the entire document content
        if (params.contentChanges.isNotEmpty()) {
            val newContent = params.contentChanges.first().text

            // Compile the document and publish diagnostics
            coroutineScope.launch {
                try {
                    val uri = java.net.URI.create(params.textDocument.uri)
                    val result = compilationService.compile(uri, newContent)

                    publishDiagnostics(params.textDocument.uri, result.diagnostics)

                    logger.debug(
                        "Published ${result.diagnostics.size} diagnostics after change for ${params.textDocument.uri}",
                    )
                } catch (e: org.codehaus.groovy.control.CompilationFailedException) {
                    logger.error("Compilation failed on file change: ${params.textDocument.uri}", e)
                }
            }
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        logger.info("Document closed: ${params.textDocument.uri}")
        // Clear diagnostics for closed document
        publishDiagnostics(params.textDocument.uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug("Document saved: ${params.textDocument.uri}")
        // Could trigger additional processing if needed
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
        coroutineScope.future {
            logger.debug(
                "Completion requested for ${params.textDocument.uri} at " +
                    "${params.position.line}:${params.position.character}",
            )

            val basicCompletions = GroovyCompletions.basic()

            // Try to get contextual completions from AST
            val contextualCompletions = CompletionProvider.getContextualCompletions(
                params.textDocument.uri,
                params.position.line,
                params.position.character,
                compilationService,
            )

            val allCompletions = basicCompletions + contextualCompletions

            logger.debug("Returning ${allCompletions.size} completions")
            Either.forLeft(allCompletions)
        }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> =
        CompletableFuture.completedFuture(unresolved)

    override fun hover(params: HoverParams): CompletableFuture<Hover> = coroutineScope.future {
        logger.debug(
            "Hover requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}",
        )

        val hoverContent = """
            ## Groovy LSP

            **Position**: Line ${params.position.line + 1}, Column ${params.position.character + 1}

            This is a basic implementation of a Groovy Language Server.

            ### Features
            - Syntax highlighting
            - Basic completions
            - Error diagnostics
            - Hover information

            ### Status
            Currently in development. More features coming soon!
        """.trimIndent()

        Hover().apply {
            contents = Either.forRight(
                MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = hoverContent
                },
            )
        }
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        CompletableFuture.completedFuture(
            Either.forLeft(emptyList()),
        )

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
        CompletableFuture.completedFuture(emptyList())
}
