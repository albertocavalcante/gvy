package com.github.albertocavalcante.groovylsp.providers.codeaction

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Factory for creating test diagnostics.
 * Centralizes diagnostic creation to avoid duplication across test files.
 */
object TestDiagnosticFactory {
    /**
     * Creates a CodeNarc diagnostic with sensible defaults.
     *
     * @param code The CodeNarc rule name
     * @param message The diagnostic message
     * @param line The line number (0-indexed)
     * @param startChar The starting character position
     * @param endChar The ending character position
     * @param source The diagnostic source (defaults to "CodeNarc")
     * @param severity The diagnostic severity
     * @return A configured Diagnostic instance
     */
    fun createCodeNarcDiagnostic(
        code: String,
        message: String,
        line: Int = 0,
        startChar: Int = 0,
        endChar: Int = 10,
        source: String? = "CodeNarc",
        severity: DiagnosticSeverity = DiagnosticSeverity.Warning,
    ): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, startChar), Position(line, endChar))
        this.message = message
        this.source = source
        this.code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(code)
        this.severity = severity
    }
}
