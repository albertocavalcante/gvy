package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/**
 * Abstract base class for diagnostic rules providing common utilities.
 *
 * Subclasses only need to implement analyzeImpl() with their specific logic.
 * This base class handles error recovery and provides helper methods for
 * creating diagnostics.
 */
abstract class AbstractDiagnosticRule : DiagnosticRule {

    override suspend fun analyze(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        // Skip analysis if there are syntax errors (unless rule explicitly allows it)
        if (context.hasErrors() && !allowsErroredCode()) {
            return emptyList()
        }

        return runCatching { analyzeImpl(uri, content, context) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                // Log error but don't propagate - rules should be isolated
                org.slf4j.LoggerFactory.getLogger(javaClass).error("Rule $id failed", error)
            }
            .getOrElse { emptyList() }
    }

    /**
     * Implement this method with rule-specific analysis logic.
     */
    protected abstract suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic>

    /**
     * Whether this rule should run even when there are syntax errors.
     * Default is false - most rules should skip errored code.
     */
    protected open fun allowsErroredCode(): Boolean = false

    /**
     * Create a diagnostic at the specified position.
     * Automatically adds analysis type prefix to the code for clarity.
     */
    protected fun diagnostic(
        range: Range,
        message: String,
        severity: DiagnosticSeverity = defaultSeverity,
        code: String? = formatDiagnosticCode(id),
    ): Diagnostic = Diagnostic(range, message, severity, "groovy-lsp", code)

    /**
     * Convenience for a single-line range.
     */
    protected fun diagnostic(
        line: Int,
        startChar: Int,
        endChar: Int,
        message: String,
        severity: DiagnosticSeverity = defaultSeverity,
    ): Diagnostic = diagnostic(
        range = Range(Position(line, startChar), Position(line, endChar)),
        message = message,
        severity = severity,
    )

    /**
     * Format diagnostic code with analysis type prefix.
     * Examples: "H:println-debug", "A:unused-import", "S:type-mismatch"
     */
    private fun formatDiagnosticCode(ruleId: String): String {
        val prefix = when (analysisType) {
            DiagnosticAnalysisType.AST -> "A"
            DiagnosticAnalysisType.HEURISTIC -> "H"
            DiagnosticAnalysisType.SEMANTIC -> "S"
        }
        return "$prefix:$ruleId"
    }
}
