package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.async.future
import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovylsp.providers.typedefinition.TypeDefinitionProvider
import com.github.albertocavalcante.groovylsp.types.GroovyTypeResolver
import kotlinx.coroutines.CoroutineScope
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
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TypeDefinitionParams
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

    // Type definition provider - created lazily
    private val typeDefinitionProvider by lazy {
        val typeResolver = GroovyTypeResolver()
        TypeDefinitionProvider(
            coroutineScope = coroutineScope,
            typeResolver = typeResolver,
            contextProvider = { uri -> createCompilationContext(uri) },
        )
    }

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

    /**
     * Creates a CompilationContext from cached compilation data.
     */
    private fun createCompilationContext(uri: java.net.URI): CompilationContext? {
        val ast = compilationService.getAst(uri)
        val astVisitor = compilationService.getAstVisitor(uri)
        val diagnostics = compilationService.getDiagnostics(uri)

        if (ast is org.codehaus.groovy.ast.ModuleNode && astVisitor != null) {
            // Use the actual compilation unit from the compilation service
            val compilationUnit = compilationService.getCompilationUnit(uri)
            if (compilationUnit == null) {
                return null
            }

            return CompilationContext(
                uri = uri,
                moduleNode = ast,
                compilationUnit = compilationUnit,
                astVisitor = astVisitor,
                workspaceRoot = null, // TODO: Get from compilation service
                classpath = compilationService.getDependencyClasspath(),
            )
        }

        return null
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
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid arguments on file open: ${params.textDocument.uri}", e)
            } catch (e: java.io.IOException) {
                logger.error("I/O error on file open: ${params.textDocument.uri}", e)
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
                } catch (e: IllegalArgumentException) {
                    logger.error("Invalid arguments on file change: ${params.textDocument.uri}", e)
                } catch (e: java.io.IOException) {
                    logger.error("I/O error on file change: ${params.textDocument.uri}", e)
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

        // Use the new HoverProvider for actual symbol information
        val hoverProvider = com.github.albertocavalcante.groovylsp.providers.hover.HoverProvider(compilationService)
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

            try {
                // Create definition provider
                val definitionProvider = DefinitionProvider(compilationService)

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
    ): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
        CompletableFuture.completedFuture(emptyList())
}
