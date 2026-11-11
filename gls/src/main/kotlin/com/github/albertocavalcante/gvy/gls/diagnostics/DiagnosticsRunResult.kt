package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import org.eclipse.lsp4j.Diagnostic

/** A terminal analysis result, bound to one current document snapshot and configuration generation. */
data class DiagnosticsRunResult(
    val uri: String,
    val version: Int?,
    val runId: Long,
    val configurationGeneration: Long,
    val diagnostics: List<Diagnostic>,
    val providerIds: List<String>,
    val failures: List<DiagnosticProviderFailure>,
) {
    // A stored field is intentional: JSON consumers must see completion rather than relying on a computed getter.
    val complete: Boolean = true
}

/** There is no retained analysis for the requested current buffer state. */
class DiagnosticsRunUnavailableException(message: String) : IllegalStateException(message)
