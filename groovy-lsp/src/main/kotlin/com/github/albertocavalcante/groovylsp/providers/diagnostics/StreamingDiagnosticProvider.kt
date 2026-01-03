package com.github.albertocavalcante.groovylsp.providers.diagnostics

import kotlinx.coroutines.flow.Flow
import org.eclipse.lsp4j.Diagnostic
import java.net.URI

/**
 * Provider interface for streaming diagnostics with configuration support.
 * Follows kotlin-lsp pattern with Flow-based async API.
 *
 * NOTE: Named StreamingDiagnosticProvider to avoid conflict with the existing
 * DiagnosticProvider interface in groovy-diagnostics/api module.
 *
 * Providers emit diagnostics as they're discovered, improving responsiveness
 * compared to returning a complete list.
 */
interface StreamingDiagnosticProvider {
    /**
     * Unique identifier for this provider.
     * Used in configuration (disabled/enabled providers) and logging.
     */
    val id: String

    /**
     * Whether this provider is enabled by default.
     * Set to false for slow or experimental providers (e.g., unused-imports).
     *
     * NOTE: Following kotlin-lsp approach - slow providers should be opt-in
     */
    val enabledByDefault: Boolean get() = true

    /**
     * Stream diagnostics for the given document.
     * Uses Flow for async streaming (kotlin-lsp pattern).
     *
     * @param uri The document URI
     * @param content The document content
     * @return Flow of diagnostics emitted as they're discovered
     */
    suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic>
}
