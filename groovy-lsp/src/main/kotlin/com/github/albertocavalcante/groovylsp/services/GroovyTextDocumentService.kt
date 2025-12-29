package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.diagnostics.codenarc.CodeNarcDiagnosticProvider
import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.codenarc.WorkspaceConfiguration
import com.github.albertocavalcante.groovylsp.compilation.CompilationResult
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.DiagnosticConfig
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.providers.SignatureHelpProvider
import com.github.albertocavalcante.groovylsp.providers.codeaction.CodeActionProvider
import com.github.albertocavalcante.groovylsp.providers.codelens.TestCodeLensProvider
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.providers.completion.JenkinsStepCompletionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.groovylsp.providers.diagnostics.DiagnosticProviderAdapter
import com.github.albertocavalcante.groovylsp.providers.implementation.ImplementationProvider
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovylsp.providers.rename.RenameProvider
import com.github.albertocavalcante.groovylsp.providers.semantictokens.JenkinsSemanticTokenProvider
import com.github.albertocavalcante.groovylsp.providers.symbols.toDocumentSymbol
import com.github.albertocavalcante.groovylsp.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.groovylsp.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigationService
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovylsp.types.GroovyTypeResolver
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
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

    // Initialize diagnostics service with provider-based architecture
    private val diagnosticsService by lazy {
        createDiagnosticsService()
    }

    /**
     * Factory method for creating DiagnosticsService with configured providers.
     *
     * NOTE: This factory pattern allows for easy testing and future extension.
     * TODO: Load DiagnosticConfig from ServerConfiguration (Phase 6)
     */
    private fun createDiagnosticsService(): DiagnosticsService {
        val workspaceRoot = compilationService.workspaceManager.getWorkspaceRoot()
        val workspaceContext = WorkspaceConfiguration(workspaceRoot, serverConfiguration)

        val providers = buildList {
            // Add CodeNarc if enabled in configuration
            if (serverConfiguration.codeNarcEnabled) {
                val codeNarcProvider = CodeNarcDiagnosticProvider(workspaceContext)
                val codeNarcAdapter = DiagnosticProviderAdapter(
                    delegate = codeNarcProvider,
                    id = "codenarc",
                    enabledByDefault = true,
                )
                add(codeNarcAdapter)
            }

            // TODO: Add more providers here as implemented
            // add(UnusedImportDiagnosticProvider())
        }

        // TODO: Load DiagnosticConfig from ServerConfiguration (Phase 6)
        val config = DiagnosticConfig()

        return DiagnosticsService(providers, config)
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

    // Source navigation service for go-to-definition on JARs
    // Typed as SourceNavigator interface for testability
    private val sourceNavigator: SourceNavigator by lazy {
        SourceNavigationService()
    }

    private val testCodeLensProvider by lazy {
        TestCodeLensProvider(compilationService)
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
        logger.debug("Publishing ${diagnostics.size} diagnostics for $uri")
        client()?.publishDiagnostics(
            PublishDiagnosticsParams().apply {
                this.uri = uri
                this.diagnostics = diagnostics
            },
        )
    }

    private fun List<Diagnostic>.containsErrors(): Boolean = any { it.severity == DiagnosticSeverity.Error }

    private suspend fun ensureCompiledOrCompileNow(uri: URI): CompilationResult? {
        compilationService.ensureCompiled(uri)?.let { return it }

        // NOTE: Heuristic / tradeoff:
        // The language client can send definition/references requests immediately after didOpen/didChange.
        // Our diagnostics pipeline compiles asynchronously, and there is a small window where compilation hasn't
        // started yet (so ensureCompiled returns null). We compile on-demand using the in-memory document text
        // to make these requests deterministic and avoid flaky e2e behavior.
        // TODO: Pre-register compilation jobs synchronously on didOpen/didChange so ensureCompiled never returns null
        // for open documents.
        val content = documentProvider.get(uri) ?: return null
        return compilationService.compileAsync(coroutineScope, uri, content).await()
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
                // Use compileAsync for proper coordination
                val result = compilationService.compileAsync(this, uri, content).await()

                ensureActive() // Ensure job wasn't cancelled before publishing

                // Publish compilation diagnostics first to keep UX responsive.
                // NOTE: Tradeoff:
                // This can result in two diagnostics publications (compile first, then CodeNarc merge),
                // but avoids blocking syntax feedback on slow lint initialization (e.g., CodeNarc ruleset load).
                publishDiagnostics(uri.toString(), result.diagnostics)

                // Skip CodeNarc when disabled or when compilation already has errors.
                if (!serverConfiguration.codeNarcEnabled || result.diagnostics.containsErrors()) {
                    return@launch
                }

                val codenarcDiagnostics = diagnosticsService.getDiagnostics(uri, content)
                val allDiagnostics = result.diagnostics + codenarcDiagnostics

                ensureActive()
                if (codenarcDiagnostics.isNotEmpty()) {
                    publishDiagnostics(uri.toString(), allDiagnostics)
                }

                logger.debug("Published ${allDiagnostics.size} diagnostics for $uri")
            } catch (e: org.codehaus.groovy.control.CompilationFailedException) {
                logger.error("Compilation failed for: $uri", e)
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
    suspend fun diagnose(uri: URI, content: String): List<Diagnostic> {
        // Compile the document and return diagnostics (does not publish them)
        val result = compilationService.compile(uri, content)
        val codenarcDiagnostics = diagnosticsService.getDiagnostics(uri, content)
        return result.diagnostics + codenarcDiagnostics
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
            // Try to get contextual completions from AST
            val uri = java.net.URI.create(params.textDocument.uri)
            val content = documentProvider.get(uri) ?: ""

            val contextualCompletions = CompletionProvider.getContextualCompletions(
                params.textDocument.uri,
                params.position.line,
                params.position.character,
                compilationService,
                content,
            )

            val isJenkinsFile = compilationService.workspaceManager.isJenkinsFile(uri)
            val jenkinsCompletions = if (isJenkinsFile) {
                val metadata = compilationService.workspaceManager.getAllJenkinsMetadata()
                if (metadata != null) {
                    JenkinsStepCompletionProvider.getStepCompletions(metadata) +
                        JenkinsStepCompletionProvider.getGlobalVariableCompletions(metadata)
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val allCompletions = basicCompletions + contextualCompletions + jenkinsCompletions

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
            sourceNavigator,
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

            val uri = java.net.URI.create(params.textDocument.uri)

            // CRITICAL: Ensure compilation completes before proceeding
            val compilationResult = ensureCompiledOrCompileNow(uri)
            if (compilationResult == null) {
                logger.warn("Document $uri not compiled, cannot provide definitions")
                return@future Either.forLeft(emptyList())
            }

            val telemetrySink = DefinitionTelemetrySink { event ->
                client()?.telemetryEvent(event)
            }

            try {
                // Create definition provider with source navigation support
                val definitionProvider = DefinitionProvider(
                    compilationService = compilationService,
                    sourceNavigator = sourceNavigator,
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
            val uri = java.net.URI.create(params.textDocument.uri)
            val compilationResult = ensureCompiledOrCompileNow(uri)
            if (compilationResult == null) {
                logger.warn("Document $uri not compiled, cannot provide references")
                return@future emptyList()
            }

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

    @Suppress("TooGenericExceptionCaught")
    override fun implementation(
        params: ImplementationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> = coroutineScope.future {
        logger.debug(
            "Implementation requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}",
        )

        try {
            val uri = java.net.URI.create(params.textDocument.uri)
            val compilationResult = ensureCompiledOrCompileNow(uri)
            if (compilationResult == null) {
                logger.warn("Document $uri not compiled, cannot provide implementations")
                return@future Either.forLeft(emptyList())
            }

            val implementationProvider = ImplementationProvider(compilationService)
            val locations = implementationProvider.provideImplementations(
                params.textDocument.uri,
                params.position,
            ).toList()

            logger.debug("Found ${locations.size} implementations")
            Either.forLeft(locations)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments finding implementations", e)
            Either.forLeft(emptyList())
        } catch (e: IllegalStateException) {
            logger.error("Invalid state finding implementations", e)
            Either.forLeft(emptyList())
        } catch (e: Exception) {
            logger.error("Unexpected error finding implementations", e)
            Either.forLeft(emptyList())
        }
    }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = coroutineScope.future {
        val uri = java.net.URI.create(params.textDocument.uri)
        val storage = ensureSymbolStorage(uri) ?: return@future emptyList()

        storage.getSymbols(uri).filter { it.shouldIncludeInDocumentSymbols() }.mapNotNull { symbol ->
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

            val actions = codeActionProvider.provideCodeActions(params)
            logger.debug("Returning ${actions.size} code actions")

            actions.map { Either.forRight<Command, CodeAction>(it) }
        }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> = coroutineScope.future {
        logger.debug("CodeLens requested for ${params.textDocument.uri}")
        val uri = java.net.URI.create(params.textDocument.uri)

        // Ensure file is compiled before providing CodeLenses
        compilationService.ensureCompiled(uri)

        testCodeLensProvider.provideCodeLenses(uri)
    }

    override fun semanticTokensFull(
        params: org.eclipse.lsp4j.SemanticTokensParams,
    ): CompletableFuture<org.eclipse.lsp4j.SemanticTokens> = coroutineScope.future {
        logger.debug("Semantic tokens requested for ${params.textDocument.uri}")

        val uri = java.net.URI.create(params.textDocument.uri)

        try {
            // Ensure document is compiled
            val compilationResult = compilationService.ensureCompiled(uri)
            if (compilationResult == null) {
                logger.warn("Document $uri not compiled, returning empty tokens")
                return@future org.eclipse.lsp4j.SemanticTokens(emptyList())
            }

            // Get AST model
            val astModel = compilationService.getAstModel(uri)
            if (astModel == null) {
                logger.warn("No AST model available for $uri, returning empty tokens")
                return@future org.eclipse.lsp4j.SemanticTokens(emptyList())
            }

            // Check if this is a Jenkins file
            val isJenkinsFile = compilationService.workspaceManager.isJenkinsFile(uri)

            // Get vars/ global variable names for semantic highlighting
            val varsNames = compilationService.workspaceManager.getJenkinsGlobalVariables()
                .map { it.name }
                .toSet()

            // Get Jenkins-specific tokens (built-in blocks + vars/ globals)
            val jenkinsTokens = JenkinsSemanticTokenProvider.getSemanticTokens(
                astModel,
                uri,
                isJenkinsFile,
                varsNames,
            )

            // TODO: Add general Groovy syntax tokens (keywords, operators, literals)
            // val groovyTokens = GroovySemanticTokenProvider.getSemanticTokens(...)

            // Combine all tokens and encode
            val allTokens = jenkinsTokens // + groovyTokens
            val encodedData = encodeSemanticTokens(allTokens)

            logger.debug("Returning ${allTokens.size} semantic tokens (${encodedData.size} integers)")
            org.eclipse.lsp4j.SemanticTokens(encodedData)
        } catch (e: Exception) {
            logger.error("Failed to generate semantic tokens for $uri", e)
            org.eclipse.lsp4j.SemanticTokens(emptyList())
        }
    }

    /**
     * Encode semantic tokens using LSP relative encoding format.
     *
     * LSP semantic tokens are encoded as a flat integer array where each token is
     * represented by 5 consecutive integers: [deltaLine, deltaStart, length, tokenType, modifiers]
     *
     * Encoding rules:
     * - deltaLine: Line offset from previous token (0 if same line)
     * - deltaStart: If deltaLine == 0, offset from previous token's start
     *               If deltaLine > 0, absolute column position (reset)
     * - length: Token length in characters
     * - tokenType: Index into SemanticTokensLegend.tokenTypes
     * - modifiers: Bitfield of indices into SemanticTokensLegend.tokenModifiers
     *
     * NOTE: Tokens MUST be sorted by line, then by startChar within each line.
     *
     * Example:
     *   Input:  [Token(line=0, char=0, len=8), Token(line=0, char=10, len=5)]
     *   Output: [0, 0, 8, type, 0,  0, 10, 5, type, 0]
     *            ^--token 1-----^   ^--token 2-----^
     */
    private fun encodeSemanticTokens(tokens: List<JenkinsSemanticTokenProvider.SemanticToken>): List<Int> {
        if (tokens.isEmpty()) {
            return emptyList()
        }

        val encoded = mutableListOf<Int>()
        var prevLine = 0
        var prevChar = 0

        // Sort tokens by line, then by character
        val sortedTokens = tokens.sortedWith(compareBy({ it.line }, { it.startChar }))

        sortedTokens.forEach { token ->
            // Calculate delta line
            val deltaLine = token.line - prevLine

            // Calculate delta char (depends on whether we changed lines)
            val deltaChar = if (deltaLine == 0) {
                // Same line: relative to previous token
                token.startChar - prevChar
            } else {
                // New line: absolute position (reset)
                token.startChar
            }

            // Add encoded token (5 integers)
            encoded.add(deltaLine)
            encoded.add(deltaChar)
            encoded.add(token.length)
            encoded.add(token.tokenType)
            encoded.add(token.tokenModifiers)

            // Update tracking for next token
            prevLine = token.line
            prevChar = token.startChar
        }

        return encoded
    }

    private suspend fun ensureSymbolStorage(uri: java.net.URI): SymbolIndex? =
        compilationService.getSymbolStorage(uri) ?: documentProvider.get(uri)?.let { content ->
            compilationService.compile(uri, content)
            compilationService.getSymbolStorage(uri)
        }

    /**
     * Wait for any ongoing diagnostics job for the given URI to complete.
     * This is useful for testing to ensure compilation is done before making assertions.
     */
    suspend fun awaitDiagnostics(uri: java.net.URI) {
        diagnosticJobs[uri]?.join()
    }

    /**
     * Notifies that CodeNarc rulesets should be reloaded.
     * Currently a placeholder - actual reload happens when diagnostics are re-run.
     * Called when .codenarc files change; should be followed by rerunDiagnosticsOnOpenFiles().
     */
    fun reloadCodeNarcRulesets() {
        logger.info("CodeNarc ruleset reload requested")
        // NOTE: Currently no caching to invalidate. Rulesets are resolved fresh on each analysis.
        // This method exists as a hook for future cache invalidation logic.
    }

    /**
     * Re-runs diagnostics on all currently open files.
     * Called after configuration changes that affect diagnostics.
     */
    fun rerunDiagnosticsOnOpenFiles() {
        logger.info("Re-running diagnostics on open files")
        documentProvider.getAllUris().forEach { uri ->
            val content = documentProvider.get(uri)
            if (content != null) {
                triggerDiagnostics(uri, content)
            }
        }
    }
}

private fun Symbol.shouldIncludeInDocumentSymbols(): Boolean = when (this) {
    is Symbol.Variable -> isParameter
    else -> true
}
