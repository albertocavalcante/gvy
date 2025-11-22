package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParserDiagnostic
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Converts parser-level diagnostics into LSP diagnostics.
 */
fun ParserDiagnostic.toLspDiagnostic(): Diagnostic {
    val severity = when (this.severity) {
        ParserSeverity.ERROR -> DiagnosticSeverity.Error
        ParserSeverity.WARNING -> DiagnosticSeverity.Warning
        ParserSeverity.INFORMATION -> DiagnosticSeverity.Information
        ParserSeverity.HINT -> DiagnosticSeverity.Hint
    }
    val start = Position(range.start.line, range.start.character)
    val end = Position(range.end.line, range.end.character)
    return Diagnostic(Range(start, end), message, severity, source, code ?: "compiler")
}
