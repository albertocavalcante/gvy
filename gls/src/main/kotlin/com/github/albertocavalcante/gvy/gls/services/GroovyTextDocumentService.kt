package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.gvy.diagnostics.codenarc.CodeNarcDiagnosticProvider
import com.github.albertocavalcante.gvy.gls.codenarc.WorkspaceConfiguration
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsOrchestrator
import com.github.albertocavalcante.gvy.gls.documentation.DocumentationProvider
import com.github.albertocavalcante.gvy.gls.providers.SignatureHelpProvider
import com.github.albertocavalcante.gvy.gls.providers.callhierarchy.CallHierarchyProvider
import com.github.albertocavalcante.gvy.gls.providers.codeaction.CodeActionProvider
import com.github.albertocavalcante.gvy.gls.providers.codelens.TestCodeLensProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.crossfile.CrossFileOperationsHandler
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.DiagnosticProviderAdapter
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDiagnosticProvider
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.CustomRulesProvider
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin.BuiltinRules
import com.github.albertocavalcante.gvy.gls.providers.folding.FoldingRangeRequestHandler
import com.github.albertocavalcante.gvy.gls.providers.highlight.DocumentHighlightProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.providers.inlayhints.InlayHintsProvider
import com.github.albertocavalcante.gvy.gls.providers.rename.RenameProvider
import com.github.albertocavalcante.gvy.gls.providers.rename.RenameRefusedException
import com.github.albertocavalcante.gvy.gls.providers.rename.WorkspaceTextCache
import com.github.albertocavalcante.gvy.gls.providers.rename.WorkspaceWalkCache
import com.github.albertocavalcante.gvy.gls.providers.selection.SelectionRangeProvider
import com.github.albertocavalcante.gvy.gls.providers.semantictokens.SemanticTokensHandler
import com.github.albertocavalcante.gvy.gls.providers.symbols.toDocumentSymbol
import com.github.albertocavalcante.gvy.gls.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.gvy.gls.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
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
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class GroovyTextDocumentServiceOptions(
    val serverConfiguration: ServerConfiguration = ServerConfiguration(),
    val client: () -> LanguageClient? = { null },
    val documentProvider: DocumentProvider = DocumentProvider(),
    val formatter: Formatter = OpenRewriteFormatterAdapter(),
    val sourceNavigator: SourceNavigator = SourceNavigationService(),
    val definitionLinkSupport: Boolean = false,
)

class GroovyTextDocumentService(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    options: GroovyTextDocumentServiceOptions = GroovyTextDocumentServiceOptions(),
) : TextDocumentService {

    @Volatile
    private var serverConfiguration: ServerConfiguration = options.serverConfiguration

    init {
        compilationService.updateServerConfiguration(options.serverConfiguration)
    }
    private val client: () -> LanguageClient? = options.client
    private val documentProvider: DocumentProvider = options.documentProvider
    private val formatter: Formatter = options.formatter
    private val sourceNavigator: SourceNavigator = options.sourceNavigator

    @Volatile
    private var definitionLinkSupport: Boolean = options.definitionLinkSupport

    private val logger = KotlinLogging.logger {}

    /** Providers are built per request; this keeps unchanged workspace files cached across rename checks. */
    private val renameDiskText = WorkspaceTextCache()
    internal val renameWalks = WorkspaceWalkCache()

    @Volatile
    private var workspaceFolders: List<Path> = emptyList()

    /**
     * Folders the client opened. The server does not declare workspace-folder change support, so these are fixed
     * at initialize; rename checks every one of them for other uses of a symbol, not just the first.
     */
    fun updateWorkspaceFolders(folders: List<Path>) {
        workspaceFolders = folders
        renameWalks.invalidate()
    }

    /** Called on any watched-file event: the directory walk behind rename checks may be out of date. */
    fun invalidateWorkspaceFiles() = renameWalks.invalidate()

    fun updateDefinitionLinkSupport(supported: Boolean) {
        definitionLinkSupport = supported
        logger.info { "Definition link support set to $supported" }
    }

    // Initialization and configuration changes share this monitor so a newly initialized
    // orchestrator cannot retain a configuration superseded while it was being constructed.
    private val diagnosticsOrchestratorDelegate = lazy(this) {
        val configuration = serverConfiguration
        DiagnosticsOrchestrator(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            diagnosticsService = createDiagnosticsService(configuration),
            documentProvider = documentProvider,
            serverConfiguration = configuration,
            client = client,
        )
    }
    private val diagnosticsOrchestrator by diagnosticsOrchestratorDelegate

    @Synchronized
    fun updateConfiguration(configuration: ServerConfiguration) {
        serverConfiguration = configuration
        compilationService.updateServerConfiguration(configuration)
        if (diagnosticsOrchestratorDelegate.isInitialized()) {
            diagnosticsOrchestrator.updateConfiguration(configuration, createDiagnosticsService(configuration))
        }
    }

    /**
     * Factory method for creating DiagnosticsService with configured providers.
     *
     * NOTE: This factory pattern allows for easy testing and future extension.
     */
    private fun createDiagnosticsService(serverConfiguration: ServerConfiguration): DiagnosticsService {
        val workspaceRoot = compilationService.workspaceManager.getWorkspaceRoot()
        val workspaceContext = WorkspaceConfiguration(workspaceRoot, serverConfiguration)

        val providers = buildList {
            val codeNarcProvider = CodeNarcDiagnosticProvider(workspaceContext)
            val codeNarcAdapter = DiagnosticProviderAdapter(
                delegate = codeNarcProvider,
                id = "codenarc",
                enabledByDefault = serverConfiguration.codeNarcEnabled,
            )
            add(codeNarcAdapter)

            val customRulesProvider = CustomRulesProvider(
                rules = BuiltinRules.getAllRules(),
                compilationService = compilationService,
                ruleConfig = serverConfiguration.diagnosticRuleConfig,
            )
            add(customRulesProvider)

            // Unused import detection with DiagnosticTag.Unnecessary for IDE dimming
            val unusedImportProvider = UnusedImportDiagnosticProvider(compilationService)
            add(unusedImportProvider)
        }

        val config = serverConfiguration.diagnosticConfig

        return DiagnosticsService(providers, config)
    }

    // Type definition provider - created lazily
    private val typeDefinitionProvider by lazy {
        TypeDefinitionProvider(
            coroutineScope = coroutineScope,
            semanticResolver = semanticTypeResolver,
            sourceNavigator = sourceNavigator,
            contextProvider = { uri -> compilationService.createContext(uri) },
        )
    }

    private val signatureHelpProvider by lazy {
        SignatureHelpProvider(
            compilationService = compilationService,
            documentProvider = documentProvider,
            semanticResolver = semanticTypeResolver,
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
    // Configured via constructor now

    private val testCodeLensProvider by lazy {
        TestCodeLensProvider(compilationService)
    }

    private val documentHighlightProvider by lazy {
        DocumentHighlightProvider(compilationService)
    }

    private val selectionRangeProvider by lazy {
        SelectionRangeProvider(compilationService, documentProvider)
    }

    private val foldingRangeHandler by lazy {
        FoldingRangeRequestHandler(compilationService, documentProvider)
    }

    private val callHierarchyProvider by lazy {
        CallHierarchyProvider(compilationService)
    }

    private val semanticTypeResolver by lazy {
        SemanticTypeResolver(compilationService.classpathService.getTypeSolver())
    }

    private val inlayHintsProvider by lazy {
        InlayHintsProvider(compilationService, semanticTypeResolver)
    }

    private val semanticTokensHandler by lazy {
        SemanticTokensHandler(compilationService, documentProvider)
    }

    private val crossFileOperationsHandler by lazy {
        CrossFileOperationsHandler(
            compilationService = compilationService,
            documentProvider = documentProvider,
            sourceNavigator = sourceNavigator,
            definitionLinkSupport = { definitionLinkSupport },
            clientTelemetry = { event -> client()?.telemetryEvent(event) },
        )
    }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> =
        coroutineScope.future {
            logger.debug { "Prepare call hierarchy requested for ${params.textDocument.uri}" }
            val uri = URI.create(params.textDocument.uri)

            compilationService.ensureCompiled(uri)

            callHierarchyProvider.prepareCallHierarchy(params)
        }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams,
    ): CompletableFuture<List<CallHierarchyIncomingCall>> = coroutineScope.future {
        logger.debug { "Incoming calls requested for ${params.item.name}" }
        callHierarchyProvider.incomingCalls(params)
    }

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams,
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> = coroutineScope.future {
        logger.debug { "Outgoing calls requested for ${params.item.name}" }
        callHierarchyProvider.outgoingCalls(params)
    }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> = coroutineScope.future {
        logger.debug {
            "Signature help requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}"
        }
        signatureHelpProvider.provideSignatureHelp(params.textDocument.uri, params.position)
    }

    private suspend fun ensureCompiledOrCompileNow(uri: URI): CompilationResult? {
        compilationService.ensureCompiled(uri)?.let { return it }

        // NOTE: Heuristic / tradeoff:
        // The language client can send definition/references requests immediately after didOpen/didChange.
        // Our diagnostics pipeline compiles asynchronously, and there is a small window where compilation
        // hasn't started yet (so ensureCompiled returns null). We compile on-demand using the in-memory
        // document text to make these requests deterministic and avoid flaky e2e behavior.
        // TODO(#564): Pre-register compilation jobs synchronously on didOpen/didChange
        //   so ensureCompiled never returns null.
        //   See: https://github.com/albertocavalcante/gvy/issues/564
        // One captured snapshot, compiled under the shared retirement policy (no raw cancellation escapes).
        val snapshot = documentProvider.getSnapshot(uri) ?: return null
        return compilationService.ensureCompiled(uri, snapshot)
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        logger.info { "Document opened: ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)
        val content = params.textDocument.text
        // Fence work of the previous lifecycle before the buffer becomes visible to it.
        compilationService.onDocumentOpened(uri)
        documentProvider.put(uri, content, params.textDocument.version)

        diagnosticsOrchestrator.trigger(uri, content)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        logger.debug { "Document changed: ${params.textDocument.uri}" }

        // We advertise full synchronization: a ranged change must never replace the whole buffer.
        if (params.contentChanges.any { it.range != null }) {
            logger.warn { "Ignoring ranged change for full-sync document: ${params.textDocument.uri}" }
            return
        }
        val version = params.textDocument.version ?: return
        val uri = URI.create(params.textDocument.uri)
        val snapshot = documentProvider.update(uri, params.contentChanges.lastOrNull()?.text, version) ?: return

        if (params.contentChanges.isNotEmpty()) {
            // Invalidate documentation cache for this document
            DocumentationProvider.invalidateDocument(uri)
        }
        // Even a version-only update retires the previous snapshot's diagnostic publication.
        diagnosticsOrchestrator.trigger(uri, snapshot.content, debounce = true)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        logger.info { "Document closed: ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)
        try {
            diagnosticsOrchestrator.close(uri)
        } finally {
            // A disconnected client must not prevent restoring disk-backed index state after close.
            // A reentrant reopen retires this close, including its cleanup of untitled-buffer indices:
            // the refresh owns only the generation this close started.
            compilationService.onClosedSourceChanged(uri)?.let { closed ->
                coroutineScope.launch {
                    compilationService.symbolIndexingService.refreshAfterClose(uri, closed)
                    compilationService.refreshSemanticFromDisk(listOf(uri))
                }
            }
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug { "Document saved: ${params.textDocument.uri}" }
        // Could trigger additional processing if needed
    }

    /**
     * Public method to get diagnostics for a file, useful for CLI "check" command.
     */
    suspend fun diagnose(uri: URI, content: String): List<Diagnostic> {
        // One immutable configuration applies throughout this request.
        val configuration = serverConfiguration
        val diagnosticsService = createDiagnosticsService(configuration)
        val result = compilationService.compile(uri, content)
        val parserEnabled = configuration.diagnosticConfig.isProviderEnabled(
            "parser",
            enabledByDefault = true,
        )
        val parserDiagnostics = if (parserEnabled) result.diagnostics else emptyList()
        val extraDiagnostics = diagnosticsService.getDiagnostics(uri, content)
        return (parserDiagnostics + extraDiagnostics).take(configuration.maxNumberOfProblems.coerceAtLeast(0))
    }

    fun refreshOpenDocuments() {
        diagnosticsOrchestrator.refreshAll()
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> =
        coroutineScope.future {
            logger.debug {
                "Completion requested for ${params.textDocument.uri} at " +
                    "${params.position.line}:${params.position.character}"
            }

            val uri = URI.create(params.textDocument.uri)
            val content = documentProvider.get(uri) ?: ""

            // Delegate all completion logic (Groovy, Jenkins, Spock) to CompletionProvider
            // CompletionProvider now uses JenkinsContextDetector internally for context-aware filtering
            val completions = CompletionProvider.getContextualCompletions(
                params.textDocument.uri,
                params.position.line,
                params.position.character,
                compilationService,
                semanticResolver = semanticTypeResolver,
                content,
            )

            logger.debug { "Returning ${completions.size} completions" }
            Either.forLeft(completions)
        }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> =
        CompletableFuture.completedFuture(unresolved)

    private val hoverRequestHandler by lazy {
        HoverRequestHandler(compilationService, documentProvider)
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> = coroutineScope.future {
        hoverRequestHandler.getHover(params)
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        coroutineScope.future {
            crossFileOperationsHandler.getDefinitions(params.textDocument.uri, params.position)
        }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> = coroutineScope.future {
        crossFileOperationsHandler.getReferences(
            params.textDocument.uri,
            params.position,
            params.context.isIncludeDeclaration,
        )
    }

    override fun typeDefinition(
        params: TypeDefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> = coroutineScope.future {
        logger.debug {
            "Type definition requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}"
        }
        // Like its siblings: a client cancel reaches the coroutine, and cancellation is never an empty answer.
        val locations = typeDefinitionProvider.typeDefinitions(params)
        logger.debug { "Found ${locations.size} type definitions" }
        Either.forLeft(locations)
    }

    override fun implementation(
        params: ImplementationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> = coroutineScope.future {
        crossFileOperationsHandler.getImplementations(params.textDocument.uri, params.position)
    }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> =
        coroutineScope.future {
            logger.debug {
                "Document highlight requested for ${params.textDocument.uri} at " +
                    "${params.position.line}:${params.position.character}"
            }

            try {
                val uri = URI.create(params.textDocument.uri)
                val compilationResult = ensureCompiledOrCompileNow(uri)
                if (compilationResult == null) {
                    logger.warn { "Document $uri not compiled, cannot provide highlights" }
                    return@future emptyList()
                }

                val highlights = documentHighlightProvider.provideHighlights(
                    params.textDocument.uri,
                    params.position,
                )

                logger.debug { "Found ${highlights.size} highlights" }
                highlights
            } catch (e: Exception) {
                rethrowIfCancellationOrError(e) // a cancelled request ends cancelled, not with no highlights
                when (e) {
                    is IllegalArgumentException -> logger.error(e) { "Invalid arguments finding highlights" }
                    is IllegalStateException -> logger.error(e) { "Invalid state finding highlights" }
                    else -> logger.error(e) { "Unexpected error finding highlights" }
                }
                emptyList()
            }
        }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = coroutineScope.future {
        val uri = URI.create(params.textDocument.uri)
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

    override fun prepareRename(
        params: PrepareRenameParams,
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> = coroutineScope.future {
        RenameProvider(
            compilationService,
            documentProvider,
            renameDiskText,
            extraRoots = { workspaceFolders },
            walks = renameWalks,
        )
            .providePrepareRename(params.textDocument.uri, params.position)
            ?.let { Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(it) }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> = coroutineScope.future {
        logger.debug {
            "Rename requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character} to '${params.newName}'"
        }

        try {
            val renameProvider = RenameProvider(
                compilationService,
                documentProvider,
                renameDiskText,
                extraRoots = { workspaceFolders },
                walks = renameWalks,
            )
            renameProvider.provideRename(
                params.textDocument.uri,
                params.position,
                params.newName,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RenameRefusedException) {
            logger.info { "Rename refused: ${e.message}" }
            throw e
        } catch (e: ResponseErrorException) {
            logger.error { "Rename failed: ${e.message}" }
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments for rename" }
            throw ResponseErrorException(
                ResponseError(
                    ResponseErrorCode.InvalidParams,
                    e.message ?: "Invalid arguments for rename",
                    null,
                ),
            )
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error during rename" }
            throw ResponseErrorException(
                ResponseError(
                    ResponseErrorCode.InternalError,
                    e.message ?: "Unexpected error during rename",
                    null,
                ),
            )
        }
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        coroutineScope.future {
            logger.debug {
                "Code action requested for ${params.textDocument.uri} at " +
                    "${params.range.start.line}:${params.range.start.character}"
            }

            val actions = codeActionProvider.provideCodeActions(params)
            logger.debug { "Returning ${actions.size} code actions" }

            actions.map { Either.forRight<Command, CodeAction>(it) }
        }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> = coroutineScope.future {
        logger.debug { "CodeLens requested for ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)

        // Ensure file is compiled before providing CodeLenses
        compilationService.ensureCompiled(uri)

        testCodeLensProvider.provideCodeLenses(uri)
    }

    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>?> =
        coroutineScope.future { selectionRangeProvider.provide(params) }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
        coroutineScope.future { foldingRangeHandler.provide(params) }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> = coroutineScope.future {
        logger.debug { "Inlay hints requested for ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)

        // TODO(#566): Read dynamic configuration from client settings.
        //  See: https://github.com/albertocavalcante/gvy/issues/566
        val compilationResult = ensureCompiledOrCompileNow(uri)
        if (compilationResult == null) {
            logger.warn { "Document $uri not compiled, cannot provide inlay hints" }
            return@future emptyList()
        }

        inlayHintsProvider.provideInlayHints(params)
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        coroutineScope.future {
            logger.debug { "Semantic tokens requested for ${params.textDocument.uri}" }
            val uri = URI.create(params.textDocument.uri)
            semanticTokensHandler.getSemanticTokens(uri)
        }

    private suspend fun ensureSymbolStorage(uri: URI): SymbolIndex? =
        compilationService.getSymbolStorage(uri) ?: documentProvider.get(uri)?.let { content ->
            compilationService.compile(uri, content)
            compilationService.getSymbolStorage(uri)
        }

    /**
     * Wait for any ongoing diagnostics job for the given URI to complete.
     * This is useful for testing to ensure compilation is done before making assertions.
     */
    suspend fun awaitDiagnostics(uri: URI) {
        diagnosticsOrchestrator.awaitDiagnostics(uri)
    }

    /**
     * Notifies that CodeNarc rulesets should be reloaded.
     * Currently a placeholder - actual reload happens when diagnostics are re-run.
     * Called when .codenarc files change; should be followed by rerunDiagnosticsOnOpenFiles().
     */
    fun reloadCodeNarcRulesets() {
        logger.info { "CodeNarc ruleset reload requested" }
        // NOTE: Currently no caching to invalidate. Rulesets are resolved fresh on each analysis.
        // This method exists as a hook for future cache invalidation logic.
    }

    /**
     * Re-runs diagnostics on all currently open files.
     * Called after configuration changes that affect diagnostics.
     */
    fun rerunDiagnosticsOnOpenFiles() {
        logger.info { "Re-running diagnostics on open files" }
        documentProvider.getAllUris().forEach { uri ->
            val content = documentProvider.get(uri)
            if (content != null) {
                diagnosticsOrchestrator.trigger(uri, content)
            }
        }
    }
}

private fun Symbol.shouldIncludeInDocumentSymbols(): Boolean = when (this) {
    is Symbol.Variable -> isParameter
    is Symbol.Method, is Symbol.Field, is Symbol.Property, is Symbol.Class, is Symbol.Import -> true
}
