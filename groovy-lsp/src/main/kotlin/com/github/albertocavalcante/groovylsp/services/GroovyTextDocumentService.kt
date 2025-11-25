package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.providers.SignatureHelpProvider
import com.github.albertocavalcante.groovylsp.providers.codeaction.CodeActionProvider
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovylsp.providers.rename.RenameProvider
import com.github.albertocavalcante.groovylsp.providers.symbols.toDocumentSymbol
import com.github.albertocavalcante.groovylsp.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.groovylsp.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.groovylsp.types.GroovyTypeResolver
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class GroovyTextDocumentService(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    private val serverConfiguration: ServerConfiguration = ServerConfiguration(),
    private val client: () -> LanguageClient?,
    private val documentProvider: DocumentProvider = DocumentProvider(),
    private val formatter: Formatter = OpenRewriteFormatterAdapter(),
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(GroovyTextDocumentService::class.java)

    // Track active diagnostic jobs per URI to cancel stale ones (debouncing/throttling)
    private val diagnosticJobs = ConcurrentHashMap<URI, Job>()

    // Initialize diagnostics service
    private val diagnosticsService by lazy {
        DiagnosticsService(compilationService.workspaceManager.getWorkspaceRoot(), serverConfiguration)
    }

    // Type definition provider - created lazily
    private val typeDefinitionProvider by lazy {
        val typeResolver = GroovyTypeResolver()
        TypeDefinitionProvider(
            coroutineScope = coroutineScope,
            typeResolver = typeResolver,
            contextProvider = { uri -> compilationService.createContext(uri) },
        )
    }

    private val signatureHelpProvider by lazy {
        SignatureHelpProvider(
            compilationService = compilationService,
            documentProvider = documentProvider,
        )
    }

    private val formattingService by lazy {
        GroovyFormattingService(formatter, documentProvider, client)
    }

    private val codeActionProvider by lazy {
        CodeActionProvider(compilationService, documentProvider, formatter)
    }

    override fun signatureHelp(
        params: org.eclipse.lsp4j.SignatureHelpParams,
    ): CompletableFuture<org.eclipse.lsp4j.SignatureHelp> = coroutineScope.future {
        logger.debug(
            "Signature help requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}",
        )
        signatureHelpProvider.provideSignatureHelp(params.textDocument.uri, params.position)
    }

    /**
     * Helper function to publish diagnostics with better readability
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        logger.info("Publishing ${diagnostics.size} diagnostics for $uri")
        client()?.publishDiagnostics(
            PublishDiagnosticsParams().apply {
                this.uri = uri
                this.diagnostics = diagnostics
            },
        )
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        logger.info("Document opened: ${params.textDocument.uri}")
        val uri = java.net.URI.create(params.textDocument.uri)
        val content = params.textDocument.text
        documentProvider.put(uri, content)

        triggerDiagnostics(uri, content)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        logger.debug("Document changed: ${params.textDocument.uri}")

        // For full sync, we get the entire document content
        if (params.contentChanges.isNotEmpty()) {
            val newContent = params.contentChanges.first().text
            val uri = java.net.URI.create(params.textDocument.uri)
            documentProvider.put(uri, newContent)

            // Invalidate documentation cache for this document
            com.github.albertocavalcante.groovylsp.documentation.DocumentationProvider.invalidateDocument(uri)

            triggerDiagnostics(uri, newContent)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        logger.info("Document closed: ${params.textDocument.uri}")
        val uri = java.net.URI.create(params.textDocument.uri)
        documentProvider.remove(uri)

        // Cancel any running diagnostics for this file
        diagnosticJobs[uri]?.cancel()
        diagnosticJobs.remove(uri)

        // Clear diagnostics for closed document
        publishDiagnostics(params.textDocument.uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug("Document saved: ${params.textDocument.uri}")
        // Could trigger additional processing if needed
    }

    @Suppress("TooGenericExceptionCaught")
    private fun triggerDiagnostics(uri: URI, content: String) {
        // Cancel any existing diagnostic job for this URI
        diagnosticJobs[uri]?.cancel()

        // Launch a new diagnostic job
        val job = coroutineScope.launch {
            try {
                val allDiagnostics = diagnose(uri, content)

                ensureActive() // Ensure job wasn't cancelled before publishing
                publishDiagnostics(uri.toString(), allDiagnostics)

                logger.info("Published ${allDiagnostics.size} diagnostics for $uri")
            } catch (e: org.codehaus.groovy.control.CompilationFailedException) {
                logger.error("Compilation failed for: $uri", e)
                val errorHandler = com.github.albertocavalcante.groovylsp.compilation.CompilationErrorHandler()
                val result = errorHandler.handleException(e, uri)
                publishDiagnostics(uri.toString(), result.diagnostics)
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid arguments for: $uri", e)
            } catch (e: java.io.IOException) {
                logger.error("I/O error for: $uri", e)
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.debug("Diagnostics job cancelled for: $uri")
                throw e
            } catch (e: Exception) {
                logger.error("Unexpected error during diagnostics for: $uri", e)
            } finally {
                // Remove job from map if it's the current one
                diagnosticJobs.remove(uri, coroutineContext[Job])
            }
        }

        diagnosticJobs[uri] = job
    }

    /**
     * Public method to get diagnostics for a file, useful for CLI "check" command.
     */
    suspend fun diagnose(uri: URI, content: String): List<Diagnostic> = withContext(Dispatchers.IO) {
        logger.info("Starting diagnostics for $uri")
        // Compile the document and return diagnostics (does not publish them)
        val result = compilationService.compile(uri, content)
        logger.info("Compilation diagnostics for $uri: ${result.diagnostics.size}")
        val codenarcDiagnostics = diagnosticsService.getDiagnostics(uri, content)
        logger.info("CodeNarc diagnostics for $uri: ${codenarcDiagnostics.size}")
        result.diagnostics + codenarcDiagnostics
    }

    fun refreshOpenDocuments() {
        coroutineScope.launch {
            documentProvider.snapshot().forEach { (uri, content) ->
                triggerDiagnostics(uri, content)
                logger.info("Triggered diagnostics refresh for $uri after dependency update")
            }
        }
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

        // Use the new HoverProvider for actual symbol information
        val hoverProvider = com.github.albertocavalcante.groovylsp.providers.hover.HoverProvider(
            compilationService,
            documentProvider,
        )
        val hover = hoverProvider.provideHover(params.textDocument.uri, params.position)

        // Return the hover if found, otherwise return an empty hover
        hover ?: Hover().apply {
            contents = Either.forRight(
                MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = "_No information available_"
                },
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - LSP service final fallback
    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        coroutineScope.future {
            logger.debug(
                "Definition requested for ${params.textDocument.uri} at " +
                    "${params.position.line}:${params.position.character}",
            )

            val telemetrySink = DefinitionTelemetrySink { event ->
                client()?.telemetryEvent(event)
            }

            try {
                // Create definition provider
                val definitionProvider = DefinitionProvider(
                    compilationService = compilationService,
                    telemetrySink = telemetrySink,
                )

                // Get definitions using Flow pattern
                val locations = definitionProvider.provideDefinitions(
                    params.textDocument.uri,
                    params.position,
                ).toList()

                logger.debug("Found ${locations.size} definitions")

                Either.forLeft(locations)
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid arguments finding definitions", e)
                Either.forLeft(emptyList())
            } catch (e: IllegalStateException) {
                logger.error("Invalid state finding definitions", e)
                Either.forLeft(emptyList())
            } catch (e: Exception) {
                logger.error("Unexpected error finding definitions", e)
                Either.forLeft(emptyList())
            }
        }

    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - LSP service final fallback
    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> = coroutineScope.future {
        logger.debug(
            "References requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}",
        )

        try {
            val referenceProvider = ReferenceProvider(compilationService)
            val locations = referenceProvider.provideReferences(
                params.textDocument.uri,
                params.position,
                params.context.isIncludeDeclaration,
            ).toList()

            logger.debug("Found ${locations.size} references")
            locations
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments finding references", e)
            emptyList()
        } catch (e: IllegalStateException) {
            logger.error("Invalid state finding references", e)
            emptyList()
        } catch (e: Exception) {
            logger.error("Unexpected error finding references", e)
            emptyList()
        }
    }

    override fun typeDefinition(
        params: TypeDefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        logger.debug(
            "Type definition requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}",
        )

        return typeDefinitionProvider.provideTypeDefinition(params).thenApply { locations ->
            logger.debug("Found ${locations.size} type definitions")
            Either.forLeft<List<Location>, List<LocationLink>>(locations)
        }.exceptionally { e ->
            logger.error("Error providing type definition", e)
            Either.forLeft(emptyList())
        }
    }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = coroutineScope.future {
        val uri = java.net.URI.create(params.textDocument.uri)
        val storage = ensureSymbolStorage(uri) ?: return@future emptyList()

        storage.getSymbols(uri).mapNotNull { symbol ->
            symbol.toDocumentSymbol()?.let { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
                ?: symbol.toSymbolInformation()?.let { Either.forLeft<SymbolInformation, DocumentSymbol>(it) }
        }
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> =
        coroutineScope.future {
            formattingService.format(params)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> = coroutineScope.future {
        logger.debug(
            "Rename requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character} to '${params.newName}'",
        )

        try {
            val renameProvider = RenameProvider(compilationService)
            renameProvider.provideRename(
                params.textDocument.uri,
                params.position,
                params.newName,
            )
        } catch (e: org.eclipse.lsp4j.jsonrpc.ResponseErrorException) {
            logger.error("Rename failed: ${e.message}")
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments for rename", e)
            throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams,
                    e.message ?: "Invalid arguments for rename",
                    null,
                ),
            )
        } catch (e: Exception) {
            logger.error("Unexpected error during rename", e)
            throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                    e.message ?: "Unexpected error during rename",
                    null,
                ),
            )
        }
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        coroutineScope.future {
            logger.debug(
                "Code action requested for ${params.textDocument.uri} at " +
                    "${params.range.start.line}:${params.range.start.character}",
            )

            val actions = withContext(Dispatchers.IO) {
                codeActionProvider.provideCodeActions(params)
            }
            logger.debug("Returning ${actions.size} code actions")

            actions.map { Either.forRight<Command, CodeAction>(it) }
            // emptyList()
        }

    private suspend fun ensureSymbolStorage(uri: java.net.URI): SymbolIndex? =
        compilationService.getSymbolStorage(uri) ?: documentProvider.get(uri)?.let { content ->
            compilationService.compile(uri, content)
            compilationService.getSymbolStorage(uri)
        }
}
