package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.diagnostics.codenarc.CodeNarcDiagnosticProvider
import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.codenarc.WorkspaceConfiguration
import com.github.albertocavalcante.groovylsp.compilation.CompilationResult
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.documentation.DocumentationProvider
import com.github.albertocavalcante.groovylsp.providers.SignatureHelpProvider
import com.github.albertocavalcante.groovylsp.providers.callhierarchy.CallHierarchyProvider
import com.github.albertocavalcante.groovylsp.providers.codeaction.CodeActionProvider
import com.github.albertocavalcante.groovylsp.providers.codelens.TestCodeLensProvider
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.groovylsp.providers.diagnostics.DiagnosticProviderAdapter
import com.github.albertocavalcante.groovylsp.providers.diagnostics.UnusedImportDetector
import com.github.albertocavalcante.groovylsp.providers.diagnostics.UnusedImportDiagnosticProvider
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.CustomRulesProvider
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin.BuiltinRules
import com.github.albertocavalcante.groovylsp.providers.folding.FoldingRangeProvider
import com.github.albertocavalcante.groovylsp.providers.highlight.DocumentHighlightProvider
import com.github.albertocavalcante.groovylsp.providers.implementation.ImplementationProvider
import com.github.albertocavalcante.groovylsp.providers.inlayhints.InlayHintsProvider
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovylsp.providers.rename.RenameProvider
import com.github.albertocavalcante.groovylsp.providers.semantictokens.GroovySemanticTokenProvider
import com.github.albertocavalcante.groovylsp.providers.semantictokens.JenkinsSemanticTokenProvider
import com.github.albertocavalcante.groovylsp.providers.symbols.toDocumentSymbol
import com.github.albertocavalcante.groovylsp.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.groovylsp.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigationService
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
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
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
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
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.IOException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

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

    private val serverConfiguration: ServerConfiguration = options.serverConfiguration
    private val client: () -> LanguageClient? = options.client
    private val documentProvider: DocumentProvider = options.documentProvider
    private val formatter: Formatter = options.formatter
    private val sourceNavigator: SourceNavigator = options.sourceNavigator

    @Volatile
    private var definitionLinkSupport: Boolean = options.definitionLinkSupport

    private val logger = KotlinLogging.logger {}

    fun updateDefinitionLinkSupport(supported: Boolean) {
        definitionLinkSupport = supported
        logger.info { "Definition link support set to $supported" }
    }

    companion object {
        /**
         * Maximum iterations for ensureAllOpenDocumentsCompiled loop.
         *
         * This is a defensive bound to avoid looping indefinitely when compilation
         * continuously fails or new files keep being added. Note that the overall
         * duration of ensureAllOpenDocumentsCompiled is still capped by
         * [MAX_COMPILATION_TIMEOUT_MS], so increasing this value does not allow the
         * method to run longer than that hard timeout.
         */
        private const val MAX_COMPILATION_ITERATIONS = 10

        /**
         * Maximum time (milliseconds) to spend in ensureAllOpenDocumentsCompiled.
         *
         * This value acts as the hard upper bound for the operation. Even though
         * the combination of [MAX_COMPILATION_ITERATIONS] and [MAX_JOB_WAIT_TIMEOUT_MS]
         * could theoretically suggest a longer duration (e.g. 10 iterations × 10 seconds
         * per joinAll = 100+ seconds), the use of this timeout (typically via
         * withTimeoutOrNull) ensures the actual worst-case latency is limited to
         * approximately this value (30 seconds).
         */
        private const val MAX_COMPILATION_TIMEOUT_MS = 30_000L

        /**
         * Maximum time (milliseconds) to wait for diagnostic jobs to complete.
         *
         * This bounds each joinAll() call so that slow or stuck jobs do not block
         * indefinitely. The overall operation is still additionally constrained by
         * [MAX_COMPILATION_TIMEOUT_MS], which defines the true worst-case latency.
         */
        private const val MAX_JOB_WAIT_TIMEOUT_MS = 10_000L
    }

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
     */
    private fun createDiagnosticsService(): DiagnosticsService {
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

    private val foldingRangeProvider by lazy {
        FoldingRangeProvider(compilationService)
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

    /**
     * Helper function to publish diagnostics with better readability
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        logger.debug { "Publishing ${diagnostics.size} diagnostics for $uri" }
        client()?.publishDiagnostics(
            PublishDiagnosticsParams().apply {
                this.uri = uri
                this.diagnostics = diagnostics
            },
        )
    }

    /**
     * Ensures all open documents are compiled and indexed.
     * Critical for cross-file features (definition, references, implementation) that depend on
     * the symbol index containing all relevant files.
     *
     * Fixes #749: Race condition where cross-file resolution fails when files are opened
     * via didOpen and definition request arrives before all files finish compiling.
     *
     * SAFEGUARDS (to prevent infinite loops and timeouts):
     * - MAX_COMPILATION_ITERATIONS: Limits loop iterations
     * - MAX_COMPILATION_TIMEOUT_MS: Overall timeout for the entire process
     * - MAX_JOB_WAIT_TIMEOUT_MS: Timeout for waiting on diagnostic jobs
     * - ensureActive(): Checks for coroutine cancellation
     * - Exception handling: Catches compilation failures to avoid retry loops
     */
    private suspend fun ensureAllOpenDocumentsCompiled() {
        // PERFORMANCE OPTIMIZATION: Check if all documents are already compiled
        // This avoids unnecessary blocking when all documents are already compiled
        val currentJob = currentCoroutineContext()[Job]
        val hasPendingJobs = diagnosticJobs.values.any { it != currentJob && it.isActive }

        // Check if all open documents are already compiled
        val allDocumentsCompiled = try {
            documentProvider.getAllUris().all { uri ->
                compilationService.getSymbolStorage(uri) != null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug(e) { "ensureAllOpenDocumentsCompiled: Error checking compilation status" }
            false
        }

        if (!hasPendingJobs && allDocumentsCompiled) {
            logger.debug { "ensureAllOpenDocumentsCompiled: No pending jobs and all documents compiled, skipping" }
            return
        }

        var iterations = 0
        val startTime = System.currentTimeMillis()

        // Loop until all open documents are compiled and indexed.
        // This handles the race condition where new documents might be opened
        // or new diagnostic jobs might be started during the process.
        // Track failed URIs to avoid retrying them in subsequent iterations
        val failedUris = mutableSetOf<URI>()

        while (true) {
            // SAFEGUARD 1: Check iteration limit to prevent infinite loops
            if (iterations >= MAX_COMPILATION_ITERATIONS) {
                logger.warn {
                    "ensureAllOpenDocumentsCompiled: Reached max iterations ($MAX_COMPILATION_ITERATIONS). " +
                        "Some documents may not be fully compiled. This may indicate a compilation loop or " +
                        "excessive file churn."
                }
                break
            }
            iterations++

            // SAFEGUARD 2: Check overall timeout to prevent indefinite blocking
            var elapsedMs = System.currentTimeMillis() - startTime
            if (elapsedMs > MAX_COMPILATION_TIMEOUT_MS) {
                logger.warn {
                    "ensureAllOpenDocumentsCompiled: Timeout after ${elapsedMs}ms " +
                        "(limit: ${MAX_COMPILATION_TIMEOUT_MS}ms). " +
                        "Some documents may not be fully compiled."
                }
                break
            }

            // SAFEGUARD 3: Check if coroutine was cancelled
            currentCoroutineContext().ensureActive()

            // Wait for all pending diagnostic jobs (which include compilation)
            // SAFEGUARD 4: Filter out current job to prevent deadlock
            val pendingJobs = diagnosticJobs.values.toList().filter { it != currentJob }

            if (pendingJobs.isNotEmpty()) {
                logger.debug {
                    "ensureAllOpenDocumentsCompiled: Iteration $iterations - " +
                        "Waiting for ${pendingJobs.size} pending compilation jobs " +
                        "(elapsed: ${elapsedMs}ms)"
                }

                // SAFEGUARD 5: Timeout on joinAll to prevent indefinite blocking
                val joinResult = withTimeoutOrNull(MAX_JOB_WAIT_TIMEOUT_MS) {
                    pendingJobs.joinAll()
                }

                if (joinResult == null) {
                    logger.warn {
                        "ensureAllOpenDocumentsCompiled: Timeout waiting for diagnostic jobs " +
                            "after ${MAX_JOB_WAIT_TIMEOUT_MS}ms. Proceeding anyway."
                    }
                }
            }

            var compiledAny = false

            // Also ensure any documents without pending jobs are compiled
            // Take a snapshot to avoid concurrent modification issues
            val urisSnapshot = try {
                documentProvider.getAllUris().toList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "ensureAllOpenDocumentsCompiled: Error getting URIs snapshot" }
                emptyList()
            }

            logger.debug {
                "ensureAllOpenDocumentsCompiled: Iteration $iterations - " +
                    "Processing ${urisSnapshot.size} open documents"
            }

            for (uri in urisSnapshot) {
                // SAFEGUARD 6: Check timeout inside loop to prevent long-running iterations
                elapsedMs = System.currentTimeMillis() - startTime
                if (elapsedMs > MAX_COMPILATION_TIMEOUT_MS) {
                    logger.warn {
                        "ensureAllOpenDocumentsCompiled: Timeout during compilation loop after ${elapsedMs}ms. " +
                            "Stopping mid-iteration."
                    }
                    return
                }

                // Check cancellation in loop
                currentCoroutineContext().ensureActive()

                // Skip URIs that failed in previous iterations
                if (uri in failedUris) {
                    continue
                }

                if (compilationService.getSymbolStorage(uri) == null) {
                    val content = documentProvider.get(uri)
                    if (content != null) {
                        logger.debug { "ensureAllOpenDocumentsCompiled: Compiling unindexed document: $uri" }

                        // SAFEGUARD 7: Catch exceptions to prevent retry loops
                        try {
                            compilationService.compile(uri, content)
                            compiledAny = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Log but continue - don't let one bad file break everything
                            // Track failed URIs to skip them in subsequent iterations
                            logger.error(e) {
                                "ensureAllOpenDocumentsCompiled: Failed to compile $uri. " +
                                    "Skipping in subsequent iterations."
                            }
                            failedUris.add(uri)
                            // Do not set compiledAny here; we only mark successful compilations
                        }
                    }
                }
            }

            if (!compiledAny) {
                // No new documents were compiled in this iteration, so all open documents
                // should now be compiled and indexed.
                // Recalculate elapsed time for accurate logging
                val completionElapsedMs = System.currentTimeMillis() - startTime
                logger.debug {
                    "ensureAllOpenDocumentsCompiled: Completed after $iterations iterations " +
                        "(${completionElapsedMs}ms)"
                }
                break
            }
        }
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
        val content = documentProvider.get(uri) ?: return null
        return compilationService.compileAsync(coroutineScope, uri, content).await()
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        logger.info { "Document opened: ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)
        val content = params.textDocument.text
        documentProvider.put(uri, content)

        triggerDiagnostics(uri, content)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        logger.debug { "Document changed: ${params.textDocument.uri}" }

        // For full sync, we get the entire document content
        if (params.contentChanges.isNotEmpty()) {
            val newContent = params.contentChanges.first().text
            val uri = URI.create(params.textDocument.uri)
            documentProvider.put(uri, newContent)

            // Invalidate documentation cache for this document
            DocumentationProvider.invalidateDocument(uri)

            triggerDiagnostics(uri, newContent)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        logger.info { "Document closed: ${params.textDocument.uri}" }
        val uri = URI.create(params.textDocument.uri)
        documentProvider.remove(uri)

        // Cancel any running diagnostics for this file
        diagnosticJobs[uri]?.cancel()
        diagnosticJobs.remove(uri)

        // Clear diagnostics for closed document
        publishDiagnostics(params.textDocument.uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug { "Document saved: ${params.textDocument.uri}" }
        // Could trigger additional processing if needed
    }

    @Suppress("TooGenericExceptionCaught")
    private fun triggerDiagnostics(uri: URI, content: String) {
        // Cancel any existing diagnostic job for this URI
        diagnosticJobs[uri]?.cancel()

        // Launch a new diagnostic job
        val job = coroutineScope.launch {
            try {
                runCatching {
                    // Use compileAsync for proper coordination
                    val result = compilationService.compileAsync(this, uri, content).await()

                    ensureActive() // Ensure job wasn't cancelled before publishing

                    val parserEnabled = serverConfiguration.diagnosticConfig.isProviderEnabled(
                        "parser",
                        enabledByDefault = true,
                    )
                    val parserDiagnostics = if (parserEnabled) result.diagnostics else emptyList()

                    // Publish compilation diagnostics first to keep UX responsive.
                    // NOTE: Tradeoff (See #564):
                    // This can result in two diagnostics publications (compile first, then provider merge),
                    // but avoids blocking syntax feedback on slow lint initialization.
                    publishDiagnostics(uri.toString(), parserDiagnostics)

                    val extraDiagnostics = diagnosticsService.getDiagnostics(uri, content)
                    val allDiagnostics = parserDiagnostics + extraDiagnostics

                    ensureActive()
                    if (extraDiagnostics.isNotEmpty()) {
                        publishDiagnostics(uri.toString(), allDiagnostics)
                    }

                    logger.debug { "Published ${allDiagnostics.size} diagnostics for $uri" }
                }.onFailure { e ->
                    when (e) {
                        is CompilationFailedException -> logger.error(e) { "Compilation failed for: $uri" }
                        is IllegalArgumentException -> logger.error(e) { "Invalid arguments for: $uri" }
                        is IOException -> logger.error(e) { "I/O error for: $uri" }
                        is CancellationException -> {
                            logger.debug { "Diagnostics job cancelled for: $uri" }
                            throw e
                        }
                        else -> logger.error(e) { "Unexpected error during diagnostics for: $uri" }
                    }
                }
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
        val parserEnabled = serverConfiguration.diagnosticConfig.isProviderEnabled(
            "parser",
            enabledByDefault = true,
        )
        val parserDiagnostics = if (parserEnabled) result.diagnostics else emptyList()
        val extraDiagnostics = diagnosticsService.getDiagnostics(uri, content)
        return parserDiagnostics + extraDiagnostics
    }

    fun refreshOpenDocuments() {
        coroutineScope.launch {
            documentProvider.snapshot().forEach { (uri, content) ->
                triggerDiagnostics(uri, content)
                logger.info { "Triggered diagnostics refresh for $uri after dependency update" }
            }
        }
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

    override fun hover(params: HoverParams): CompletableFuture<Hover> = coroutineScope.future {
        logger.debug {
            "Hover requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}"
        }

        val uri = URI.create(params.textDocument.uri)

        // Ensure AST is prepared (compiles if needed)
        // This is still needed because getSession expects cached result
        ensureAstPrepared(uri)

        val session = compilationService.getSession(uri)
        session?.features?.hoverProvider?.getHover(params) ?: Hover().apply {
            contents = Either.forRight(
                MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = "_No information available (Not Compiled)_"
                },
            )
        }
    }

    private suspend fun ensureAstPrepared(documentUri: URI) {
        val hasAst = compilationService.getAst(documentUri) != null
        if (hasAst) return

        val content = documentProvider.get(documentUri) ?: return
        runCatching {
            compilationService.compile(documentUri, content)
        }.onFailure { error ->
            logger.debug(error) { "GroovyTextDocumentService: failed to compile $documentUri before hover" }
        }
    }

    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - LSP service final fallback
    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> =
        coroutineScope.future {
            logger.debug {
                "Definition requested for ${params.textDocument.uri} at " +
                    "${params.position.line}:${params.position.character}"
            }

            val uri = URI.create(params.textDocument.uri)

            val telemetrySink = DefinitionTelemetrySink { event ->
                client()?.telemetryEvent(event)
            }

            try {
                // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
                // Fixes #749: Race condition where target file may not be indexed yet
                ensureAllOpenDocumentsCompiled()

                // CRITICAL: Ensure compilation completes before proceeding
                val compilationResult = ensureCompiledOrCompileNow(uri)
                if (compilationResult == null) {
                    logger.warn { "Document $uri not compiled, cannot provide definitions" }
                    return@future Either.forLeft(emptyList())
                }
                // Create definition provider with source navigation support
                val definitionProvider = DefinitionProvider(
                    compilationService = compilationService,
                    sourceNavigator = sourceNavigator,
                    telemetrySink = telemetrySink,
                )

                if (definitionLinkSupport) {
                    val links = definitionProvider.provideDefinitionLinks(
                        params.textDocument.uri,
                        params.position,
                    ).toList()
                    if (links.isNotEmpty()) {
                        logger.info {
                            "Returning ${links.size} definition links (first=${links.first().targetUri})"
                        }
                        return@future Either.forRight(links)
                    }
                }

                // Get definitions using Flow pattern
                val locations = definitionProvider.provideDefinitions(
                    params.textDocument.uri,
                    params.position,
                ).toList()

                if (locations.isNotEmpty()) {
                    logger.info { "Returning ${locations.size} definition locations (first=${locations.first().uri})" }
                } else {
                    logger.debug { "Found 0 definitions" }
                }

                Either.forLeft(locations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                logger.error(e) { "Invalid arguments finding definitions" }
                Either.forLeft(emptyList())
            } catch (e: IllegalStateException) {
                logger.error(e) { "Invalid state finding definitions" }
                Either.forLeft(emptyList())
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error finding definitions" }
                Either.forLeft(emptyList())
            }
        }

    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - LSP service final fallback
    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> = coroutineScope.future {
        logger.debug {
            "References requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}"
        }

        try {
            val uri = URI.create(params.textDocument.uri)

            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            val compilationResult = ensureCompiledOrCompileNow(uri)
            if (compilationResult == null) {
                logger.warn { "Document $uri not compiled, cannot provide references" }
                return@future emptyList()
            }

            val referenceProvider = ReferenceProvider(compilationService)
            val locations = referenceProvider.provideReferences(
                params.textDocument.uri,
                params.position,
                params.context.isIncludeDeclaration,
            ).toList()

            logger.debug { "Found ${locations.size} references" }
            locations
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments finding references" }
            emptyList()
        } catch (e: IllegalStateException) {
            logger.error(e) { "Invalid state finding references" }
            emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error finding references" }
            emptyList()
        }
    }

    override fun typeDefinition(
        params: TypeDefinitionParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        logger.debug {
            "Type definition requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}"
        }

        return typeDefinitionProvider.provideTypeDefinition(params).thenApply { locations ->
            logger.debug { "Found ${locations.size} type definitions" }
            Either.forLeft<List<Location>, List<LocationLink>>(locations)
        }.exceptionally { e ->
            logger.error(e) { "Error providing type definition" }
            Either.forLeft(emptyList())
        }
    }

    private val implementationProvider by lazy {
        ImplementationProvider(compilationService)
    }

    override fun implementation(
        params: ImplementationParams,
    ): CompletableFuture<Either<List<Location>, List<LocationLink>>> = coroutineScope.future {
        logger.debug {
            "Implementation requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character}"
        }

        try {
            val uri = URI.create(params.textDocument.uri)

            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            val compilationResult = ensureCompiledOrCompileNow(uri)
            if (compilationResult == null) {
                logger.warn { "Document $uri not compiled, cannot provide implementations" }
                return@future Either.forLeft(emptyList())
            }

            val locations = implementationProvider.provideImplementations(
                params.textDocument.uri,
                params.position,
            ).toList()

            logger.debug { "Found ${locations.size} implementations" }
            Either.forLeft(locations)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments finding implementations" }
            Either.forLeft(emptyList())
        } catch (e: IllegalStateException) {
            logger.error(e) { "Invalid state finding implementations" }
            Either.forLeft(emptyList())
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error finding implementations" }
            Either.forLeft(emptyList())
        }
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

    @Suppress("TooGenericExceptionCaught")
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> = coroutineScope.future {
        logger.debug {
            "Rename requested for ${params.textDocument.uri} at " +
                "${params.position.line}:${params.position.character} to '${params.newName}'"
        }

        try {
            val renameProvider = RenameProvider(compilationService)
            renameProvider.provideRename(
                params.textDocument.uri,
                params.position,
                params.newName,
            )
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

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
        coroutineScope.future {
            logger.debug { "Folding range requested for ${params.textDocument.uri}" }
            val uri = URI.create(params.textDocument.uri)

            // Ensure file is compiled before providing folding ranges
            val compilationResult = ensureCompiledOrCompileNow(uri)
            if (compilationResult == null) {
                logger.warn { "Document $uri not compiled, cannot provide folding ranges" }
                return@future emptyList()
            }

            foldingRangeProvider.provideFoldingRanges(uri)
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

            try {
                // Ensure document is compiled
                val compilationResult = compilationService.ensureCompiled(uri)
                if (compilationResult == null) {
                    logger.warn { "Document $uri not compiled, returning empty tokens" }
                    return@future SemanticTokens(emptyList())
                }

                // Get AST model
                val astModel = compilationService.getAstModel(uri)
                if (astModel == null) {
                    logger.warn { "No AST model available for $uri, returning empty tokens" }
                    return@future SemanticTokens(emptyList())
                }

                // Check if this is a Jenkins file
                val jenkinsCapabilities = compilationService.workspaceManager.getJenkinsCapabilities()
                val isJenkinsFile = jenkinsCapabilities?.isJenkinsFile(uri) ?: false

                // Get vars/ global variable names for semantic highlighting
                val varsNames = jenkinsCapabilities?.getGlobalVariables()
                    ?.map { it.name }
                    ?.toSet()
                    ?: emptySet()

                // Detect unused imports for dimming (semantic token modifier)
                val moduleNode = compilationService.getAst(uri) as? ModuleNode
                val unusedImports = moduleNode?.let {
                    UnusedImportDetector.detectUnusedImports(it).toSet()
                } ?: emptySet()

                // Get general Groovy semantic tokens for ALL files
                val groovyTokens = GroovySemanticTokenProvider.getSemanticTokens(
                    astModel,
                    uri,
                    unusedImports = unusedImports,
                    moduleNode = moduleNode,
                )

                // Get Jenkins-specific tokens (built-in blocks + vars/ globals) only for Jenkins files
                val jenkinsTokens = if (isJenkinsFile) {
                    JenkinsSemanticTokenProvider.getSemanticTokens(
                        astModel,
                        uri,
                        isJenkinsFile,
                        varsNames,
                    )
                } else {
                    emptyList()
                }

                // Combine all tokens and encode
                val allTokens = combineTokens(groovyTokens, jenkinsTokens)
                    .sortedWith(
                        compareBy<JenkinsSemanticTokenProvider.SemanticToken> { it.line }
                            .thenBy { it.startChar }
                            .thenBy { it.length }
                            .thenBy { it.tokenType }
                            .thenBy { it.tokenModifiers },
                    )
                val encodedData = encodeSemanticTokens(allTokens)

                logger.debug { "Returning ${allTokens.size} semantic tokens (${encodedData.size} integers)" }
                SemanticTokens(encodedData)
            } catch (e: Exception) {
                logger.error(e) { "Failed to generate semantic tokens for $uri" }
                SemanticTokens(emptyList())
            }
        }

    /**
     * Combine Groovy and Jenkins semantic tokens into a single unified list.
     *
     * Both token types use the same data structure, so we convert them to a common format
     * and merge them together for encoding.
     */
    private fun combineTokens(
        groovyTokens: List<GroovySemanticTokenProvider.SemanticToken>,
        jenkinsTokens: List<JenkinsSemanticTokenProvider.SemanticToken>,
    ): List<JenkinsSemanticTokenProvider.SemanticToken> {
        // Convert GroovySemanticTokenProvider tokens to JenkinsSemanticTokenProvider tokens
        val convertedGroovyTokens = groovyTokens.map { token ->
            JenkinsSemanticTokenProvider.SemanticToken(
                line = token.line,
                startChar = token.startChar,
                length = token.length,
                tokenType = token.tokenType,
                tokenModifiers = token.tokenModifiers,
            )
        }

        return convertedGroovyTokens + jenkinsTokens
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
        diagnosticJobs[uri]?.join()
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
                triggerDiagnostics(uri, content)
            }
        }
    }
}

private fun Symbol.shouldIncludeInDocumentSymbols(): Boolean = when (this) {
    is Symbol.Variable -> isParameter
    is Symbol.Method, is Symbol.Field, is Symbol.Property, is Symbol.Class, is Symbol.Import -> true
}
