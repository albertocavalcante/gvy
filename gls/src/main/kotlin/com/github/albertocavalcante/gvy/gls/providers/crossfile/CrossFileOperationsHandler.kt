@file:Suppress(
    "LongParameterList", // Handler requires multiple dependencies for cross-file operations
)

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.gls.compilation.CompilationEnsurer
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsOrchestrator
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionProvider
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.gvy.gls.providers.implementation.ImplementationProvider
import com.github.albertocavalcante.gvy.gls.providers.references.ReferenceProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
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

    private val telemetrySink by lazy {
        DefinitionTelemetrySink { event -> clientTelemetry(event) }
    }

    private val definitionProvider by lazy {
        DefinitionProvider(
            compilationService = compilationService,
            sourceNavigator = sourceNavigator,
            telemetrySink = telemetrySink,
        )
    }

    private val referenceProvider by lazy {
        ReferenceProvider(compilationService)
    }

    private val implementationProvider by lazy {
        ImplementationProvider(compilationService)
    }

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

        return runSuspendCatching {
            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            // CRITICAL: Ensure compilation completes before proceeding
            ensureCompiledOrCompileNow(documentUri)
                ?: run {
                    logger.warn { "Document $documentUri not compiled, cannot provide definitions" }
                    return@runSuspendCatching Either.forLeft<List<Location>, List<LocationLink>>(emptyList())
                }

            // Try LocationLink format if supported
            if (definitionLinkSupport()) {
                val links = definitionProvider.provideDefinitionLinks(uri, position).toList()
                if (links.isNotEmpty()) {
                    logger.debug {
                        "Returning ${links.size} definition links (first=${links.first().targetUri})"
                    }
                    return@runSuspendCatching Either.forRight<List<Location>, List<LocationLink>>(links)
                }
            }

            // Fall back to Location format
            val locations = definitionProvider.provideDefinitions(uri, position).toList()
            logger.debug {
                if (locations.isNotEmpty()) {
                    "Returning ${locations.size} definition locations (first=${locations.first().uri})"
                } else {
                    "Found 0 definitions"
                }
            }

            Either.forLeft<List<Location>, List<LocationLink>>(locations)
        }.getOrElse { e ->
            logException(e, "finding definitions")
            Either.forLeft(emptyList())
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

        val documentUri = URI.create(uri)

        return runSuspendCatching {
            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            ensureCompiledOrCompileNow(documentUri)
                ?: run {
                    logger.warn { "Document $documentUri not compiled, cannot provide references" }
                    return@runSuspendCatching emptyList()
                }

            referenceProvider.provideReferences(uri, position, includeDeclaration)
                .toList()
                .also { logger.debug { "Found ${it.size} references" } }
        }.getOrElse { e ->
            logException(e, "finding references")
            emptyList()
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

        val documentUri = URI.create(uri)

        return runSuspendCatching {
            // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
            // Fixes #749: Race condition where target file may not be indexed yet
            ensureAllOpenDocumentsCompiled()

            ensureCompiledOrCompileNow(documentUri)
                ?: run {
                    logger.warn { "Document $documentUri not compiled, cannot provide implementations" }
                    return@runSuspendCatching Either.forLeft<List<Location>, List<LocationLink>>(emptyList())
                }

            implementationProvider.provideImplementations(uri, position)
                .toList()
                .also { logger.debug { "Found ${it.size} implementations" } }
                .let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
        }.getOrElse { e ->
            logException(e, "finding implementations")
            Either.forLeft(emptyList())
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

    /**
     * Helper function to log exceptions with appropriate context.
     * Uses idiomatic `when` expression for type-based message selection.
     */
    private fun logException(e: Throwable, operation: String) {
        val message = when (e) {
            is IllegalArgumentException -> "Invalid arguments $operation"
            is IllegalStateException -> "Invalid state $operation"
            else -> "Unexpected error $operation"
        }
        logger.error(e) { message }
    }
}
