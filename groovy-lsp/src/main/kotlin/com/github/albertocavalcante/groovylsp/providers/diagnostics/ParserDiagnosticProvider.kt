package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for parser-level diagnostics (syntax errors, compilation errors).
 *
 * This provider extracts diagnostics from the parser's compilation results,
 * which include syntax errors and other compilation issues detected during
 * the parsing phase.
 *
 * NOTE: Parser diagnostics are extracted from cached parse results when available,
 * making this provider very fast (no re-parsing needed).
 */
class ParserDiagnosticProvider(private val compilationService: GroovyCompilationService) :
    StreamingDiagnosticProvider {

    companion object {
        private val logger = LoggerFactory.getLogger(ParserDiagnosticProvider::class.java)
    }

    override val id: String = "parser"

    override val enabledByDefault: Boolean = true

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
        logger.debug("Providing parser diagnostics for: $uri")

        val diagnostics =
            runCatching {
                // Get diagnostics from compilation service
                // NOTE: This uses cached parse results, so it's very fast
                compilationService.getDiagnostics(uri)
            }
                .onFailure { throwable ->
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is Error -> throw throwable
                        else -> logger.error("Failed to provide parser diagnostics for $uri", throwable)
                    }
                }
                // Don't re-throw - allow other providers to continue
                .getOrDefault(emptyList())

        logger.debug("Found ${diagnostics.size} parser diagnostics for $uri")

        // Emit each diagnostic
        diagnostics.forEach { diagnostic ->
            emit(diagnostic)
        }
    }
}
