// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress(
    "LongParameterList", // Handler requires multiple dependencies for cross-file operations
)

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.gls.compilation.CompilationEnsurer
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionInputs
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionProvider
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionTelemetrySink
import com.github.albertocavalcante.gvy.gls.providers.implementation.ImplementationProvider
import com.github.albertocavalcante.gvy.gls.providers.references.ReferenceProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.utils.failingAsInternalError
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.gvy.semantics.db.ImplementationDocument
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

private const val MAX_IMPLEMENTATION_SNAPSHOT_ATTEMPTS = 3

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
 * @property definitionLinkSupport Lambda returning whether definition links are supported
 * @property clientTelemetry Lambda for sending telemetry events to the client
 */
class CrossFileOperationsHandler(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val sourceNavigator: SourceNavigator,
    private val definitionLinkSupport: () -> Boolean,
    private val clientTelemetry: (Any) -> Unit,
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
        ReferenceProvider(compilationService, compilationService.getWorkspaceSymbolIndex())
    }

    private val implementationProvider by lazy {
        ImplementationProvider(compilationService)
    }

    internal fun capture(uri: String, position: Position, definition: Boolean = false): NavigationRequest {
        val document = URI.create(uri)
        if (documentProvider !== compilationService.documents) throw navigationChanged("Different document authority")
        val source = compilationService.captureSnapshotSource(document)
        val jenkins = if (definition) JenkinsQuerySnapshot.capture(compilationService, document) else null
        return NavigationRequest(
            source,
            Position(position.line, position.character),
            compilationService.referenceReadiness(source),
            jenkins,
            definition && definitionLinkSupport(),
        )
    }

    internal suspend fun definitions(request: NavigationRequest): Either<List<Location>, List<LocationLink>> =
        failingAsInternalError("finding definitions") {
            request.query(compilationService, Either.forLeft<List<Location>, List<LocationLink>>(emptyList())) { held ->
                val inputs = DefinitionInputs(compilationService, documentProvider, request, held)
                inputs.query {
                    val uri = request.uri.toString()
                    if (request.links) {
                        val links = definitionProvider.lookupDefinitionLinks(uri, request.position, inputs).getOrThrow()
                        if (links.isEmpty()) Either.forLeft(emptyList()) else Either.forRight(links)
                    } else {
                        Either.forLeft(definitionProvider.lookupDefinitions(uri, request.position, inputs).getOrThrow())
                    }
                }
            }
        }

    internal suspend fun references(request: NavigationRequest, includeDeclaration: Boolean): List<Location> =
        failingAsInternalError("finding references") {
            request.query(compilationService, emptyList()) { held ->
                referenceProvider.navigationReferences(request.uri, request.position, held, includeDeclaration) { scan ->
                    ensureAllOpenDocumentsCompiled()
                    request.readiness.prepareOpenSources(compilationService)
                    if (request.source.buffer == null) compilationService.ensureCompiled(request.uri)
                    when (val graph = request.readiness.query { qualified ->
                        if (!qualified.matches(request.uri, held)) {
                            throw navigationUnavailable("Reference origin and index have different producers")
                        }
                        scan(qualified)
                    }) {
                        is SnapshotQuery.Answer -> graph.value
                        SnapshotQuery.Stale -> throw navigationChanged("Reference inputs changed")
                        SnapshotQuery.Missing -> throw navigationUnavailable("Reference index is missing")
                        is SnapshotQuery.Failure -> throw graph.cause
                    }
                }.toList()
            }
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

        return failingAsInternalError("finding definitions") {
            withCurrentOrigin(documentUri) { findDefinitions(documentUri, position) }
        }
    }

    private suspend fun findDefinitions(
        documentUri: URI,
        position: Position,
    ): Either<List<Location>, List<LocationLink>> {
        val uri = documentUri.toString()
        // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
        // Fixes #749: Race condition where target file may not be indexed yet
        ensureAllOpenDocumentsCompiled()

        // CRITICAL: Ensure compilation completes before proceeding
        if (ensureCompiledOrCompileNow(documentUri) == null) {
            logger.warn { "Document $documentUri not compiled, cannot provide definitions" }
            return Either.forLeft(emptyList())
        }

        // One lookup per request: a link-capable client gets links, or an honest empty/failed answer, never a
        // second resolution of the same position. A failed lookup is thrown to the response boundary.
        if (definitionLinkSupport()) {
            val links = definitionProvider.lookupDefinitionLinks(uri, position).getOrThrow()
            logger.debug { "Returning ${links.size} definition links" }
            return if (links.isEmpty()) Either.forLeft(emptyList()) else Either.forRight(links)
        }

        val locations = definitionProvider.lookupDefinitions(uri, position).getOrThrow()
        logger.debug {
            if (locations.isNotEmpty()) {
                "Returning ${locations.size} definition locations (first=${locations.first().uri})"
            } else {
                "Found 0 definitions"
            }
        }
        return Either.forLeft(locations)
    }

    /**
     * Get references to the symbol at the given position.
     *
     * @param uri Document URI
     * @param position Cursor position
     * @param includeDeclaration Whether to include the declaration/definition in results
     * @return List of reference locations; a failed scan is a [ResponseErrorException], not a partial list
     */
    suspend fun getReferences(uri: String, position: Position, includeDeclaration: Boolean): List<Location> {
        logger.debug {
            "References requested for $uri at ${position.line}:${position.character}"
        }

        val documentUri = URI.create(uri)

        return failingAsInternalError("finding references") {
            withCurrentOrigin(documentUri) {
                // CRITICAL: Ensure ALL open documents are compiled before cross-file resolution
                // Fixes #749: Race condition where target file may not be indexed yet
                ensureAllOpenDocumentsCompiled()

                if (ensureCompiledOrCompileNow(documentUri) == null) {
                    logger.warn { "Document $documentUri not compiled, cannot provide references" }
                    emptyList()
                } else {
                    referenceProvider.provideReferences(uri, position, includeDeclaration)
                        .toList()
                        .also { logger.debug { "Found ${it.size} references" } }
                }
            }
        }
    }

    /**
     * Runs a request whose answer is computed from [uri]'s open buffer, and reports ContentModified instead of an
     * answer about text the client no longer shows: the origin buffer was edited, closed or reopened while the
     * request ran. That holds for a failure too: a lookup that broke because the origin changed under it is
     * ContentModified, not an InternalError the client would show; the original failure is logged at debug.
     * A [ResponseErrorException] from [block] is already a deliberate answer and passes unchanged, as does
     * cancellation. Retirements of other work (a configuration refresh, another file closing) are retried by the
     * compilation service and do not fail the request; clients drop ContentModified silently for definition and
     * references, so it must mean the origin really changed.
     */
    private suspend fun <T> withCurrentOrigin(uri: URI, block: suspend () -> T): T {
        val origin = documentProvider.getSnapshot(uri)
        // runSuspendCatching is the response boundary's catch: it rethrows cancellation and Errors, so only an
        // ordinary failure is held back here, to be judged against the origin below.
        val result = runSuspendCatching { block() }
        val failure = result.exceptionOrNull()
        if (failure is ResponseErrorException) throw failure
        if (origin != null && documentProvider.getSnapshot(uri) !== origin) {
            failure?.let { logger.debug { "Request failed after $uri changed: ${it.message}" } }
            throw contentModified("$uri changed during the request")
        }
        return result.getOrThrow()
    }

    private fun contentModified(message: String) =
        ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, message, null))

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

        return failingAsInternalError("finding implementations") {
            withCurrentOrigin(documentUri) {
                val snapshot = acquireImplementationSnapshot(documentUri)
                if (snapshot == null) {
                    logger.warn { "Document $documentUri not compiled, cannot provide implementations" }
                    Either.forLeft(emptyList())
                } else {
                    implementationProvider.provideImplementations(documentUri, position, snapshot)
                        .toList()
                        .also { logger.debug { "Found ${it.size} implementations" } }
                        .let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
                }
            }
        }
    }

    private suspend fun acquireImplementationSnapshot(uri: URI): Map<URI, ImplementationDocument>? {
        repeat(MAX_IMPLEMENTATION_SNAPSHOT_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val generation = compilationService.beginImplementationQuery()
            ensureAllOpenDocumentsCompiled()
            val origin = ensureCompiledOrCompileNow(uri) ?: return null
            val snapshot = compilationService.captureImplementationSnapshot(generation) ?: return@repeat
            val required = documentProvider.snapshot().toMutableMap()
            origin.sourceText?.let { required.putIfAbsent(uri, it) }
            val missing = required.filter { (document, text) ->
                snapshot[document]?.sourceText != text
            }
            if (missing.isEmpty()) return snapshot
            // An old compile can restore AST/symbol caches after its semantic publication was invalidated.
            // Cache-hit compilation republishes missing facts; an unrelated malformed buffer may still have none.
            missing.forEach { (document, text) -> compilationService.compile(document, text) }
            compilationService.captureImplementationSnapshot(generation)?.let { repaired ->
                // Failed parsing can leave the prior document in the database; never navigate its stale text.
                return repaired.filter { (document, facts) ->
                    document !in required || facts.sourceText == required[document]
                }
            }
        }
        // Retry only after an observed invalidation, never because a valid query has no implementations.
        throw contentModified("Workspace changed during implementation acquisition")
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

        // One captured snapshot, compiled under the shared retirement policy (no raw cancellation escapes).
        val snapshot = documentProvider.getSnapshot(uri) ?: return null
        return compilationService.ensureCompiled(uri, snapshot)
    }
}
