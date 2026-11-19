// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticProvider
import com.github.albertocavalcante.gvy.diagnostics.codenarc.CodeNarcDiagnosticProvider
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
        if (delegate is CodeNarcDiagnosticProvider) {
            delegate.analyzeStreaming(content, uri) { emit(it) }
        } else {
            delegate.analyze(content, uri).forEach { emit(it) }
        }
    }
}
