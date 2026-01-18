@file:Suppress(
    "TooGenericExceptionCaught", // LSP service final fallback
    "LongParameterList", // Handler requires multiple dependencies for cross-file operations
    "ReturnCount", // Multiple return paths for error handling are intentional
)

package com.github.albertocavalcante.groovylsp.providers.crossfile

import com.github.albertocavalcante.groovylsp.compilation.CompilationEnsurer
import com.github.albertocavalcante.groovylsp.compilation.CompilationResult
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.diagnostics.DiagnosticsOrchestrator
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.groovylsp.providers.implementation.ImplementationProvider
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI

/**
 * Handles cross-file LSP operations (definition, references, implementation).
 *
 * These operations share common patterns:
 * - ensureAllOpenDocumentsCompiled() - ensures target files are indexed
 * - ensureCompiledOrCompileNow() - ensures current file is compiled
 * - Provider instantiation and delegation
 * - Consistent error handling
 *
 * Extracting these operations reduces code duplication and centralizes cross-file resolution logic.
 *
 * @property compilationService Service for compiling and accessing AST/symbol tables
 * @property documentProvider Provider for accessing document content
 * @property sourceNavigator Navigator for resolving source locations (including JARs)
 * @property diagnosticsOrchestrator Orchestrator for managing diagnostic jobs
 * @property definitionLinkSupport Lambda returning whether definition links are supported
 * @property clientTelemetry Lambda for sending telemetry events to the client
 * @property coroutineScope Coroutine scope for async compilation
 */
class CrossFileOperationsHandler(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator,
    private val diagnosticsOrchestrator: DiagnosticsOrchestrator,
    private val definitionLinkSupport: () -> Boolean,
    private val clientTelemetry: (Any) -> Unit,
    private val coroutineScope: CoroutineScope,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get definitions for the symbol at the given position.
     *
     * Supports both Location and LocationLink formats depending on client capabilities.
     *
     * @param uri Document URI
     * @param position Cursor position
     * @return Either list of Locations or list of LocationLinks
     */
    suspend fun getDefinitions(uri: String, position: Position): Either<List<Location>, List<LocationLink>> {
        logger.debug {
            "Definition requested for $uri at ${position.line}:${position.character}"
        }

        val documentUri = URI.create(uri)
        val telemetrySink = DefinitionTelemetrySink { event ->
            clientTelemetry(event)
        }

        try {
            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            // CRITICAL: Ensure compilation completes before proceeding
            val compilationResult = ensureCompiledOrCompileNow(documentUri)
            if (compilationResult == null) {
                logger.warn { "Document $documentUri not compiled, cannot provide definitions" }
                return Either.forLeft(emptyList())
            }

            // Create definition provider with source navigation support
            val definitionProvider = DefinitionProvider(
                compilationService = compilationService,
                sourceNavigator = sourceNavigator,
                telemetrySink = telemetrySink,
            )

            // Try LocationLink format if supported
            if (definitionLinkSupport()) {
                val links = definitionProvider.provideDefinitionLinks(uri, position).toList()
                if (links.isNotEmpty()) {
                    logger.debug {
                        "Returning ${links.size} definition links (first=${links.first().targetUri})"
                    }
                    return Either.forRight(links)
                }
            }

            // Fall back to Location format
            val locations = definitionProvider.provideDefinitions(uri, position).toList()

            if (locations.isNotEmpty()) {
                logger.debug { "Returning ${locations.size} definition locations (first=${locations.first().uri})" }
            } else {
                logger.debug { "Found 0 definitions" }
            }

            return Either.forLeft(locations)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments finding definitions" }
            return Either.forLeft(emptyList())
        } catch (e: IllegalStateException) {
            logger.error(e) { "Invalid state finding definitions" }
            return Either.forLeft(emptyList())
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error finding definitions" }
            return Either.forLeft(emptyList())
        }
    }

    /**
     * Get references to the symbol at the given position.
     *
     * @param uri Document URI
     * @param position Cursor position
     * @param includeDeclaration Whether to include the declaration/definition in results
     * @return List of reference locations
     */
    suspend fun getReferences(uri: String, position: Position, includeDeclaration: Boolean): List<Location> {
        logger.debug {
            "References requested for $uri at ${position.line}:${position.character}"
        }

        try {
            val documentUri = URI.create(uri)

            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            val compilationResult = ensureCompiledOrCompileNow(documentUri)
            if (compilationResult == null) {
                logger.warn { "Document $documentUri not compiled, cannot provide references" }
                return emptyList()
            }

            val referenceProvider = ReferenceProvider(compilationService)
            val locations = referenceProvider.provideReferences(
                uri,
                position,
                includeDeclaration,
            ).toList()

            logger.debug { "Found ${locations.size} references" }
            return locations
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments finding references" }
            return emptyList()
        } catch (e: IllegalStateException) {
            logger.error(e) { "Invalid state finding references" }
            return emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error finding references" }
            return emptyList()
        }
    }

    /**
     * Get implementations of the symbol at the given position.
     *
     * Finds concrete implementations of interfaces and abstract methods.
     *
     * @param uri Document URI
     * @param position Cursor position
     * @return Either list of Locations (LocationLink not currently supported for implementations)
     */
    suspend fun getImplementations(uri: String, position: Position): Either<List<Location>, List<LocationLink>> {
        logger.debug {
            "Implementation requested for $uri at ${position.line}:${position.character}"
        }

        try {
            val documentUri = URI.create(uri)

            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            val compilationResult = ensureCompiledOrCompileNow(documentUri)
            if (compilationResult == null) {
                logger.warn { "Document $documentUri not compiled, cannot provide implementations" }
                return Either.forLeft(emptyList())
            }

            val implementationProvider = ImplementationProvider(compilationService)
            val locations = implementationProvider.provideImplementations(uri, position).toList()

            logger.debug { "Found ${locations.size} implementations" }
            return Either.forLeft(locations)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments finding implementations" }
            return Either.forLeft(emptyList())
        } catch (e: IllegalStateException) {
            logger.error(e) { "Invalid state finding implementations" }
            return Either.forLeft(emptyList())
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error finding implementations" }
            return Either.forLeft(emptyList())
        }
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
        CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticsOrchestrator.getDiagnosticJobsMap(),
        ).ensureAllCompiled()
    }

    /**
     * Ensures the document is compiled, compiling on-demand if needed.
     *
     * NOTE: Heuristic / tradeoff:
     * The language client can send definition/references requests immediately after didOpen/didChange.
     * Our diagnostics pipeline compiles asynchronously, and there is a small window where compilation
     * hasn't started yet (so ensureCompiled returns null). We compile on-demand using the in-memory
     * document text to make these requests deterministic and avoid flaky e2e behavior.
     *
     * TODO(#564): Pre-register compilation jobs synchronously on didOpen/didChange
     *   so ensureCompiled never returns null.
     *   See: https://github.com/albertocavalcante/gvy/issues/564
     *
     * @param uri Document URI
     * @return Compilation result if successful, null otherwise
     */
    private suspend fun ensureCompiledOrCompileNow(uri: URI): CompilationResult? {
        compilationService.ensureCompiled(uri)?.let { return it }

        val content = documentProvider.get(uri) ?: return null
        return compilationService.compileAsync(coroutineScope, uri, content).await()
    }
}
