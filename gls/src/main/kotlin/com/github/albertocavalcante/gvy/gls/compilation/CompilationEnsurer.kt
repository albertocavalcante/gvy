package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.net.URI

/**
 * Ensures every open document's compilation facts (its parse and symbol index) are current before a
 * cross-file request (definition, references, implementation) reads them.
 *
 * Readiness is the compilation of each open buffer's current text, not its diagnostics job: a document
 * whose symbol index was built from exactly its current text is ready even while optional lint providers
 * still run, and any other document awaits the compile of that text through
 * [GroovyCompilationService.ensureCompiled], sharing a compatible pending compile; that compile is owned by the
 * compilation service, so cancelling one request never retires work other requests share (#1187). An index of
 * older text is never accepted as ready.
 *
 * Fixes #749: Race condition where cross-file resolution fails when files are opened
 * via didOpen and definition request arrives before all files finish compiling.
 *
 * SAFEGUARDS:
 * - [maxTimeMs]: overall bound; on expiry the request proceeds with the facts available
 * - each document is compiled at most once per call; a failing compile is logged and skipped
 * - cancellation of the calling request propagates
 */
class CompilationEnsurer(
    private val documentProvider: DocumentProvider,
    private val compilationService: GroovyCompilationService,
    private val maxTimeMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun ensureAllCompiled() {
        val startTime = System.currentTimeMillis()
        val completed = withTimeoutOrNull(maxTimeMs) {
            documentProvider.getAllUris().forEach { uri -> ensureCurrent(uri) }
        }
        if (completed == null) {
            logger.warn {
                "ensureAllOpenDocumentsCompiled: Timeout after ${maxTimeMs}ms. " +
                    "Some documents may not be fully compiled."
            }
        } else {
            logger.debug {
                "ensureAllOpenDocumentsCompiled: Completed in ${System.currentTimeMillis() - startTime}ms"
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // one bad document must not fail the request for the others
    private suspend fun ensureCurrent(uri: URI) {
        currentCoroutineContext().ensureActive()
        val snapshot = documentProvider.getSnapshot(uri) ?: return // closed meanwhile
        val indexing = compilationService.symbolIndexingService
        if (indexing.isIndexed(uri, snapshot)) return
        try {
            repeat(MAX_ATTEMPTS) {
                // Owned by the compilation service, not this request: cancelling the request leaves the shared
                // compile running for its other waiters; retirements are re-resolved by its retry policy.
                val result = compilationService.ensureCompiled(uri, snapshot)
                // A compile retired after it finished returns with its writes discarded: try once more. A buffer
                // without an AST never indexes, and an edited one is the next request's concern.
                if (result.ast == null || indexing.isIndexed(uri, snapshot) ||
                    documentProvider.getSnapshot(uri) !== snapshot
                ) {
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseErrorException) {
            // Another document kept changing: navigate with what is current rather than fail this request.
            logger.debug(e) { "ensureAllOpenDocumentsCompiled: $uri kept changing; continuing without it" }
        } catch (e: Exception) {
            logger.error(e) { "ensureAllOpenDocumentsCompiled: Failed to compile $uri" }
        }
    }

    companion object {
        /** Upper bound (milliseconds) on waiting for the open documents to compile. */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** Compiles of one document per request, to recover from a retirement after completion. */
        private const val MAX_ATTEMPTS = 2
    }
}
