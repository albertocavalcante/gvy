package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.diagnostics.api.DiagnosticProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Diagnostic
import java.net.URI

/**
 * Adapter that wraps the legacy DiagnosticProvider interface (from diagnostics.api)
 * into the new StreamingDiagnosticProvider interface.
 *
 * NOTE: This adapter bridges the old List-based API to the new Flow-based API,
 * allowing existing providers (like CodeNarcDiagnosticProvider) to work with
 * the new enhanced DiagnosticsService without modification.
 *
 * TODO: Eventually migrate all providers to implement StreamingDiagnosticProvider directly
 * and remove this adapter.
 */
class DiagnosticProviderAdapter(
    private val delegate: DiagnosticProvider,
    override val id: String,
    override val enabledByDefault: Boolean = true,
) : StreamingDiagnosticProvider {

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
        // Call the legacy analyze method and emit all results
        val diagnostics = delegate.analyze(content, uri)
        diagnostics.forEach { emit(it) }
    }
}
