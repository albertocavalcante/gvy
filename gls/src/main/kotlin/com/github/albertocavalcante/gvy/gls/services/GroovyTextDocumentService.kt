// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.gvy.diagnostics.codenarc.CodeNarcDiagnosticProvider
import com.github.albertocavalcante.gvy.diagnostics.codenarc.HierarchicalRulesetResolver
import com.github.albertocavalcante.gvy.gls.codenarc.WorkspaceConfiguration
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.CompilationEnsurer
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsOrchestrator
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsRunResult
import com.github.albertocavalcante.gvy.gls.documentation.DocumentationProvider
import com.github.albertocavalcante.gvy.gls.execution.discoverEntryPoints
import com.github.albertocavalcante.gvy.gls.providers.SignatureHelpProvider
import com.github.albertocavalcante.gvy.gls.providers.callhierarchy.CallHierarchyProvider
import com.github.albertocavalcante.gvy.gls.providers.codeaction.CodeActionProvider
import com.github.albertocavalcante.gvy.gls.providers.codelens.TestCodeLensProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.forClient
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
import com.github.albertocavalcante.gvy.gls.providers.symbols.buildDocumentOutline
import com.github.albertocavalcante.gvy.gls.providers.symbols.flattenOutline
import com.github.albertocavalcante.gvy.gls.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.jenkins.JenkinsFileDetector
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.google.gson.JsonPrimitive
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
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
import org.eclipse.lsp4j.Position
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
    val documentAnalysisSupported: () -> Boolean = { false },
    val documentAnalysisSession: (URI) -> String? = { null },
    val documentProvider: DocumentProvider? = null,
    val formatter: Formatter = OpenRewriteFormatterAdapter(),
    val sourceNavigator: SourceNavigator = SourceNavigationService(),
    val definitionLinkSupport: Boolean = false,
    /** Builds the CodeNarc ruleset resolver from the configured Jenkins pipeline file predicate (test seam). */
    val codeNarcRulesetResolverFactory: ((java.net.URI) -> Boolean) -> HierarchicalRulesetResolver =
        { isJenkinsPipelineFile -> HierarchicalRulesetResolver(isJenkinsPipelineFile = isJenkinsPipelineFile) },
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
    private val documentAnalysisSupported: () -> Boolean = options.documentAnalysisSupported
    private val documentAnalysisSession: (URI) -> String? = options.documentAnalysisSession
    private val documentProvider: DocumentProvider = options.documentProvider ?: compilationService.documents
    private val formatter: Formatter = options.formatter
    private val sourceNavigator: SourceNavigator = options.sourceNavigator
    private val codeNarcRulesetResolverFactory = options.codeNarcRulesetResolverFactory

    /** The resolver behind the current CodeNarc provider, so [reloadCodeNarcRulesets] can clear its caches. */
    @Volatile
    private var codeNarcRulesetResolver: HierarchicalRulesetResolver? = null

    @Volatile
    private var definitionLinkSupport: Boolean = options.definitionLinkSupport

    // Read once from the client's initialize capabilities; see updateLabelDetailsSupport.
    @Volatile
    private var labelDetailsSupport: Boolean = false

    @Volatile
    private var snippetSupport: Boolean = false

    // LSP: a client that does not say it renders `DocumentSymbol` trees gets flat `SymbolInformation`.
    @Volatile
    private var hierarchicalDocumentSymbolSupport: Boolean = false

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

    /** Whether the client renders `CompletionItem.labelDetails` (LSP 3.17), from its initialize capabilities. */
    fun updateLabelDetailsSupport(supported: Boolean) {
        labelDetailsSupport = supported
    }

    /** Whether the client renders nested `DocumentSymbol`s, from its initialize capabilities. */
    fun updateHierarchicalDocumentSymbolSupport(supported: Boolean) {
        hierarchicalDocumentSymbolSupport = supported
    }

    fun updateSnippetSupport(supported: Boolean) {
        snippetSupport = supported
    }

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
            documentAnalysisSupported = documentAnalysisSupported,
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
            val jenkinsFiles = JenkinsFileDetector(serverConfiguration.jenkinsConfig.filePatterns)
            val codeNarcProvider = CodeNarcDiagnosticProvider(
                workspaceContext,
                // Jenkins pipeline files get the Jenkins profile, the workspace's other sources their own (#1236).
                rulesetResolver = codeNarcRulesetResolverFactory(jenkinsFiles::isJenkinsFile)
                    .also { codeNarcRulesetResolver = it },
            )
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
                configuration = serverConfiguration,
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
            semanticResolver = semanticTypeResolver,
            contextProvider = { uri -> compilationService.createContextWithSource(uri) },
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
        CodeActionProvider(compilationService, documentProvider, formatter, documentAnalysisSupported)
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
        SemanticTypeResolver(
            compilationService.classpathService.getTypeSolver(),
            compilationService.getWorkspaceSymbolIndex(),
        )
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

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> {
        val document = params.textDocument.uri
        val position = Position(params.position.line, params.position.character)
        return coroutineScope.future {
            val uri = URI.create(document)
            queryEditorFact(uri) { snapshot ->
                callHierarchyProvider.prepareCallHierarchy(uri, position, snapshot).onEach { item ->
                    item.data = checkNotNull(snapshot.sourceIdentity)
                }
            }
        }
    }

    private suspend fun <T> queryEditorFact(
        uri: URI,
        compute: (CompiledDocumentSnapshot) -> List<T>,
    ): List<T> = when (val result = compilationService.queryEditorSnapshot(uri) { compute(it) }) {
        is SnapshotQuery.Answer -> result.value
        SnapshotQuery.Missing -> emptyList()
        SnapshotQuery.Stale -> throw ResponseErrorException(
            ResponseError(ResponseErrorCode.ContentModified, "Document changed during request", null),
        )
        is SnapshotQuery.Failure -> throw result.cause
    }

    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams,
    ): CompletableFuture<List<CallHierarchyIncomingCall>> {
        val document = params.item.uri
        val start = params.item.selectionRange.start
        val position = Position(start.line, start.character)
        val identity = preparedIdentity(params.item.data)
        return coroutineScope.future {
            val uri = URI.create(document)
            val readiness = compilationService.referenceReadiness(uri)
            requirePreparedFence(identity, readiness.sourceIdentity)
            val initialOrigin = when (val acquisition = compilationService.acquireEditorSnapshot(uri)) {
                is SnapshotAcquisition.Available -> acquisition
                SnapshotAcquisition.Stale, SnapshotAcquisition.Missing -> throw contentModified()
                is SnapshotAcquisition.Failure -> throw acquisition.cause
            }
            if (!initialOrigin.isCurrent()) throw contentModified()
            requirePreparedIdentity(identity, initialOrigin.snapshot.sourceIdentity)
            CompilationEnsurer(documentProvider, compilationService).ensureAllCompiled()
            readiness.prepareOpenSources(compilationService)
            compilationService.ensureCompiled(uri)
            when (val result = readiness.query { references ->
                val held = mutableMapOf<URI, SnapshotAcquisition.Ready>()
                suspend fun capture(sourceUri: URI): CompiledDocumentSnapshot {
                    val existing = held[sourceUri]
                    if (existing != null) return existing.snapshot
                    val acquisition = compilationService.acquireSnapshot(
                        sourceUri,
                        mode = references.modeFor(sourceUri),
                    )
                    val ready = when (acquisition) {
                        is SnapshotAcquisition.Ready -> acquisition
                        SnapshotAcquisition.Stale -> throw contentModified()
                        SnapshotAcquisition.Missing, is SnapshotAcquisition.Partial ->
                            throw referenceUnavailable(sourceUri)
                        is SnapshotAcquisition.Failure -> throw acquisition.cause
                    }
                    if (!references.matches(sourceUri, ready.snapshot)) throw contentModified()
                    held[sourceUri] = ready
                    return ready.snapshot
                }
                val origin = capture(uri)
                requirePreparedIdentity(identity, origin.sourceIdentity)
                val answer = callHierarchyProvider.incomingCalls(uri, position, origin, references.index, ::capture)
                if (!initialOrigin.isCurrent() || held.values.any { !it.isCurrent() }) throw contentModified()
                answer.forEach { call ->
                    call.from.data = checkNotNull(held.getValue(URI.create(call.from.uri)).snapshot.sourceIdentity)
                }
                answer
            }) {
                is SnapshotQuery.Answer -> result.value
                SnapshotQuery.Missing -> throw referenceUnavailable(uri)
                SnapshotQuery.Stale -> throw contentModified()
                is SnapshotQuery.Failure -> throw result.cause
            }
        }
    }

    private fun contentModified() = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Inputs changed during call hierarchy", null),
    )

    private fun referenceUnavailable(uri: URI) = ResponseErrorException(
        ResponseError(ResponseErrorCode.RequestFailed, "Reference source unavailable for $uri", null),
    )

    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams,
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> {
        val document = params.item.uri
        val start = params.item.selectionRange.start
        val position = Position(start.line, start.character)
        val identity = preparedIdentity(params.item.data)
        return coroutineScope.future {
            val uri = URI.create(document)
            when (val result = compilationService.queryEditorSnapshot(uri) { snapshot ->
                requirePreparedIdentity(identity, snapshot.sourceIdentity)
                callHierarchyProvider.outgoingCalls(uri, position, snapshot).onEach { call ->
                    call.to.data = checkNotNull(snapshot.sourceIdentity)
                }
            }) {
                is SnapshotQuery.Answer -> result.value
                SnapshotQuery.Missing, SnapshotQuery.Stale -> throw contentModified()
                is SnapshotQuery.Failure -> throw result.cause
            }
        }
    }

    private fun requirePreparedIdentity(identity: String?, sourceIdentity: String?) {
        if (identity == null) {
            throw ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "Missing call hierarchy identity", null))
        }
        if (identity != sourceIdentity) throw contentModified()
    }

    private fun requirePreparedFence(identity: String?, fence: String) {
        if (identity == null || '/' !in identity) {
            throw ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "Missing call hierarchy identity", null))
        }
        if (identity.substringBeforeLast('/') != fence) throw contentModified()
    }

    private fun preparedIdentity(data: Any?): String? = when (data) {
        is String -> data
        is JsonPrimitive -> if (data.isString) data.asString else null
        else -> null
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
        documentProvider.put(
            uri,
            content,
            DocumentVersion.of(params.textDocument.version),
            documentAnalysisSession(uri),
        )
        diagnosticsOrchestrator.documentChanged(uri)

        diagnosticsOrchestrator.trigger(uri, content)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        logger.debug { "Document changed: ${params.textDocument.uri}" }

        // We advertise full synchronization: a ranged change must never replace the whole buffer.
        if (params.contentChanges.any { it.range != null }) {
            logger.warn { "Ignoring ranged change for full-sync document: ${params.textDocument.uri}" }
            return
        }
        val version = DocumentVersion.ofOrNull(params.textDocument.version) ?: return
        val uri = URI.create(params.textDocument.uri)
        val snapshot = documentProvider.update(uri, params.contentChanges.lastOrNull()?.text, version) ?: return
        diagnosticsOrchestrator.documentChanged(uri)

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
        codeActionProvider.forgetDocument(uri)
        try {
            diagnosticsOrchestrator.close(uri)
        } finally {
            // A disconnected client must not prevent restoring disk-backed index state after close.
            // A reentrant reopen retires this close, including its cleanup of untitled-buffer indices:
            // the refresh owns only the generation this close started.
            val shadowedListed = compilationService.onShadowingDocumentClosed(uri)
            compilationService.onClosedSourceChanged(uri)?.let { closed ->
                if (shadowedListed != null) {
                    // Opened under another spelling than the listed one (#1446): the listed copy stands for the
                    // file again, so this spelling's facts go rather than being rebuilt from the same disk file.
                    // Dropped here, on the notification thread, so a reopen that follows cannot lose its own.
                    compilationService.invalidateCache(uri)
                } else {
                    coroutineScope.launch {
                        compilationService.symbolIndexingService.refreshAfterClose(uri, closed)
                        compilationService.refreshSemanticFromDisk(listOf(uri))
                    }
                }
            }
        }
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug { "Document saved: ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)
        val snapshot = documentProvider.getSnapshot(uri) ?: return
        diagnosticsOrchestrator.trigger(uri, snapshot.content)
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
            Either.forLeft(completions.forClient(labelDetailsSupport, snippetSupport))
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

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> {
        val document = params.textDocument.uri
        val position = Position(params.position.line, params.position.character)
        return coroutineScope.future {
            logger.debug {
                "Document highlight requested for $document at ${position.line}:${position.character}"
            }

            val uri = URI.create(document)
            val highlights = queryEditorFact(uri) { documentHighlightProvider.provideHighlights(uri, position, it) }
            logger.debug { "Found ${highlights.size} highlights" }
            highlights
        }
    }

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val request = runCatching {
            val uriText = params.textDocument.uri
            val uri = URI.create(uriText)
            val source = compilationService.captureSnapshotSource(uri)
            Triple(uriText, source, documentProvider.getSnapshot(uri))
        }
        val hierarchical = hierarchicalDocumentSymbolSupport
        return coroutineScope.future {
            val (uriText, source, original) = request.getOrThrow()
            val uri = source.uri
            if (documentProvider !== compilationService.documents || source.buffer?.snapshot !== original) {
                throw ResponseErrorException(
                    ResponseError(ResponseErrorCode.ContentModified, "Document changed during symbol discovery", null),
                )
            }
            val query: suspend (CompiledDocumentSnapshot) ->
            List<Either<SymbolInformation, DocumentSymbol>> = { held ->
                val symbols = SymbolIndex().buildFromVisitor(held.parseResult.astModel).getSymbols(uri)
                val outline = buildDocumentOutline(symbols, TextIndex(held.sourceText))
                if (hierarchical) {
                    outline.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
                } else {
                    flattenOutline(uriText, outline).map { Either.forLeft<SymbolInformation, DocumentSymbol>(it) }
                }
            }
            val result = compilationService.querySymbolsWithWorkerRetry(
                source,
                Phases.CANONICALIZATION,
                if (source.buffer == null) ParseMode.MINIMAL else ParseMode.WORKSPACE,
                query,
            )
            when (result) {
                is SnapshotQuery.Answer -> result.value
                SnapshotQuery.Missing -> emptyList()
                SnapshotQuery.Stale -> throw ResponseErrorException(
                    ResponseError(ResponseErrorCode.ContentModified, "Document changed during symbol discovery", null),
                )
                is SnapshotQuery.Failure -> throw result.cause
            }
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

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        val request = runCatching {
            val uri = URI.create(params.textDocument.uri)
            val source = compilationService.captureSnapshotSource(uri)
            Triple(source, documentProvider.getSnapshot(uri), testCodeLensProvider.captureDetectors())
        }
        return coroutineScope.future {
            val (source, original, detectors) = request.getOrThrow()
            val uri = source.uri
            if (documentProvider !== compilationService.documents || source.buffer?.snapshot !== original) {
                throw ResponseErrorException(
                    ResponseError(
                        ResponseErrorCode.ContentModified,
                        "Document changed during code lens discovery",
                        null,
                    ),
                )
            }
            val query: suspend (CompiledDocumentSnapshot) ->
            List<CodeLens> = { held ->
                val parsed = held.parseResult
                val testLenses = testCodeLensProvider.provideCodeLenses(uri, parsed, detectors)
                val executionLenses = executionCodeLenses(uri, parsed.ast)
                requireCurrentTestDetectors(detectors)
                testLenses + executionLenses
            }
            val result = compilationService.queryCapturedSnapshot(
                source,
                Phases.CANONICALIZATION,
                if (source.buffer == null) ParseMode.MINIMAL else ParseMode.WORKSPACE,
                query,
            )
            when (result) {
                is SnapshotQuery.Answer -> {
                    requireCurrentTestDetectors(detectors)
                    currentCoroutineContext().ensureActive()
                    result.value
                }
                SnapshotQuery.Missing -> emptyList()
                SnapshotQuery.Stale -> throw ResponseErrorException(
                    ResponseError(
                        ResponseErrorCode.ContentModified,
                        "Document changed during code lens discovery",
                        null,
                    ),
                )
                is SnapshotQuery.Failure -> throw result.cause
            }
        }
    }

    private fun requireCurrentTestDetectors(
        detectors: List<com.github.albertocavalcante.gvy.testing.api.TestFrameworkDetector>,
    ) {
        if (!testCodeLensProvider.detectorsCurrent(detectors)) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.ContentModified, "Test frameworks changed during discovery", null),
            )
        }
    }

    private fun executionCodeLenses(uri: URI, module: ModuleNode?): List<CodeLens> =
        module?.let { discoverEntryPoints(it) }.orEmpty().map { entry ->
            val position = Position(entry.line, 0)
            CodeLens(
                Range(position, position),
                Command(
                    "$(play) Run File",
                    "groovy.runFile",
                    listOf(mapOf("uri" to uri.toString(), "entryPoint" to entry.id)),
                ),
                null,
            )
        }

    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>?> {
        val request = runCatching { selectionRangeProvider.capture(params) }
        return coroutineScope.future { selectionRangeProvider.provide(request.getOrThrow()) }
    }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> {
        val request = runCatching { foldingRangeHandler.capture(params) }
        return coroutineScope.future { foldingRangeHandler.provide(request.getOrThrow()) }
    }

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

    /**
     * Wait for any ongoing diagnostics job for the given URI to complete.
     * This is useful for testing to ensure compilation is done before making assertions.
     */
    suspend fun awaitDiagnostics(uri: URI) {
        diagnosticsOrchestrator.awaitDiagnostics(uri)
    }

    /**
     * Waits until every open buffer has been compiled and indexed, so a workspace-wide query sees the buffers a
     * client has just opened. A buffer that cannot compile is skipped: its failure belongs to its own requests.
     */
    internal suspend fun awaitOpenDocumentsIndexed() {
        documentProvider.getAllUris().forEach { uri ->
            runCatching { compilationService.ensureCompiled(uri) }.onFailure(::rethrowIfCancellationOrError)
        }
    }

    /** Returns the retained terminal analysis for the current buffer, including provider failures. */
    internal suspend fun awaitCurrentDiagnostics(uri: URI, version: Int? = null): DiagnosticsRunResult =
        diagnosticsOrchestrator.awaitCurrentDiagnostics(uri, version)

    /**
     * Notifies that CodeNarc rulesets should be reloaded: clears the resolver's cached project detection and default
     * rulesets. Called when .codenarc files change; should be followed by rerunDiagnosticsOnOpenFiles().
     */
    fun reloadCodeNarcRulesets() {
        logger.info { "CodeNarc ruleset reload requested" }
        // Clears the cached project detection and default rulesets; workspace ruleset files are read per analysis.
        codeNarcRulesetResolver?.reloadRulesets()
        diagnosticsOrchestrator.invalidateWorkspaceConfiguration()
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
