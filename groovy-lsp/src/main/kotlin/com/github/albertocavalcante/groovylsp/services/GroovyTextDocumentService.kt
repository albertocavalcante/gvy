package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovyformatter.OpenRewriteFormatter
import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.providers.SignatureHelpProvider
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovylsp.providers.symbols.toDocumentSymbol
import com.github.albertocavalcante.groovylsp.providers.symbols.toSymbolInformation
import com.github.albertocavalcante.groovylsp.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.groovylsp.types.GroovyTypeResolver
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
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
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.max

fun interface Formatter {
    fun format(text: String): String
}

internal enum class FormatterStatus {
    SUCCESS,
    NO_OP,
    ERROR,
    NOT_FOUND,
}

internal data class FormatterTelemetryEvent(
    val uri: String,
    val status: FormatterStatus,
    val durationMs: Long,
    val ignoredOptions: Boolean,
    val errorMessage: String? = null,
)

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val DEFAULT_TAB_SIZE = 4
private val KNOWN_FORMATTING_OPTION_KEYS = setOf("tabSize", "insertSpaces")

class GroovyTextDocumentService(
    private val coroutineScope: CoroutineScope,
    private val compilationService: GroovyCompilationService,
    private val serverConfiguration: ServerConfiguration = ServerConfiguration(),
    private val client: () -> LanguageClient?,
    private val documentProvider: DocumentProvider = DocumentProvider(),
    private val formatter: Formatter = OpenRewriteFormatterAdapter(),
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(GroovyTextDocumentService::class.java)
    private val optionsWarningLogged = AtomicBoolean(false)

    // Track active diagnostic jobs per URI to cancel stale ones (debouncing/throttling)
    private val diagnosticJobs = ConcurrentHashMap<URI, Job>()

    // Initialize diagnostics service
    private val diagnosticsService by lazy {
        DiagnosticsService(compilationService.getWorkspaceRoot(), serverConfiguration)
    }

    // Type definition provider - created lazily
    private val typeDefinitionProvider by lazy {
        val typeResolver = GroovyTypeResolver()
        TypeDefinitionProvider(
            coroutineScope = coroutineScope,
            typeResolver = typeResolver,
            contextProvider = { uri -> createCompilationContext(uri) },
        )
    }

    private val signatureHelpProvider by lazy {
        SignatureHelpProvider(
            compilationService = compilationService,
            documentProvider = documentProvider,
        )
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

    /**
     * Creates a CompilationContext from cached compilation data.
     */
    private fun createCompilationContext(uri: java.net.URI): CompilationContext? {
        val parseResult = compilationService.getParseResult(uri) ?: return null
        val ast = parseResult.ast ?: return null

        return CompilationContext(
            uri = uri,
            moduleNode = ast,
            compilationUnit = parseResult.compilationUnit,
            astVisitor = parseResult.astVisitor,
            workspaceRoot = compilationService.getWorkspaceRoot(),
            classpath = compilationService.getDependencyClasspath(),
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

    private fun triggerDiagnostics(uri: URI, content: String) {
        // Cancel any existing diagnostic job for this URI
        diagnosticJobs[uri]?.cancel()

        // Launch a new diagnostic job
        val job = coroutineScope.launch {
            try {
                // Compile the document and publish diagnostics
                val result = compilationService.compile(uri, content)
                val codenarcDiagnostics = diagnosticsService.getDiagnostics(uri, content)
                val allDiagnostics = result.diagnostics + codenarcDiagnostics

                ensureActive() // Ensure job wasn't cancelled before publishing
                publishDiagnostics(uri.toString(), allDiagnostics)

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
            val uriString = params.textDocument.uri
            logger.debug("Formatting requested for {}", uriString)
            val startNanos = System.nanoTime()
            val uri = java.net.URI.create(uriString)

            val options = params.options
            val ignoredOptions = shouldMarkOptionsIgnored(options)
            maybeLogIgnoredOptions(ignoredOptions)

            val currentContent = documentProvider.get(uri)
            if (currentContent == null) {
                publishTelemetry(uriString, FormatterStatus.NOT_FOUND, durationMs = 0, ignoredOptions = ignoredOptions)
                return@future emptyList<TextEdit>()
            }

            coroutineContext.ensureActive()

            val formattedResult = runCatching { formatter.format(currentContent) }
            val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND

            val formattedContent = formattedResult.getOrElse { throwable ->
                val failureMessage = throwable.message ?: throwable.javaClass.simpleName
                logger.warn("Formatter failed for {}: {}", uriString, failureMessage)
                if (logger.isDebugEnabled) {
                    logger.debug("Formatter failure details for {}", uriString, throwable)
                }
                publishTelemetry(
                    uriString,
                    FormatterStatus.ERROR,
                    durationMs = durationMs,
                    ignoredOptions = ignoredOptions,
                    errorMessage = failureMessage,
                )
                return@future emptyList<TextEdit>()
            }

            coroutineContext.ensureActive()

            if (formattedContent == currentContent) {
                publishTelemetry(
                    uriString,
                    FormatterStatus.NO_OP,
                    durationMs = durationMs,
                    ignoredOptions = ignoredOptions,
                )
                return@future emptyList<TextEdit>()
            }

            publishTelemetry(
                uriString,
                FormatterStatus.SUCCESS,
                durationMs = durationMs,
                ignoredOptions = ignoredOptions,
            )

            val range = currentContent.toFullDocumentRange()
            listOf(TextEdit(range, formattedContent))
        }

    private fun maybeLogIgnoredOptions(ignoredOptions: Boolean) {
        if (ignoredOptions && optionsWarningLogged.compareAndSet(false, true)) {
            logger.info("DocumentFormattingOptions are not yet supported; using OpenRewrite defaults.")
        }
    }

    private fun publishTelemetry(
        uri: String,
        status: FormatterStatus,
        durationMs: Long,
        ignoredOptions: Boolean,
        errorMessage: String? = null,
    ) {
        client()?.telemetryEvent(
            FormatterTelemetryEvent(
                uri = uri,
                status = status,
                durationMs = durationMs,
                ignoredOptions = ignoredOptions,
                errorMessage = errorMessage,
            ),
        )
    }

    private suspend fun ensureSymbolStorage(uri: java.net.URI): SymbolIndex? =
        compilationService.getSymbolStorage(uri) ?: documentProvider.get(uri)?.let { content ->
            compilationService.compile(uri, content)
            compilationService.getSymbolStorage(uri)
        }
}

private fun shouldMarkOptionsIgnored(options: FormattingOptions?): Boolean {
    if (options == null) {
        return false
    }
    if (options.tabSize != DEFAULT_TAB_SIZE || !options.isInsertSpaces) {
        return true
    }
    if (options.isTrimTrailingWhitespace || options.isInsertFinalNewline || options.isTrimFinalNewlines) {
        return true
    }
    return options.keys.any { it !in KNOWN_FORMATTING_OPTION_KEYS }
}

private fun String.toFullDocumentRange(): Range {
    var line = 0
    var lastLineStart = 0
    this.indices.forEach { index ->
        if (this[index] == '\n') {
            line++
            lastLineStart = index + 1
        }
    }

    var column = length - lastLineStart
    if (column > 0 && this[length - 1] == '\r') {
        column--
    }
    column = max(column, 0)

    return Range(
        Position(0, 0),
        Position(line, column),
    )
}

private class OpenRewriteFormatterAdapter : Formatter {
    private val delegate = OpenRewriteFormatter()

    override fun format(text: String): String = delegate.format(text)
}
