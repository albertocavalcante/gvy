package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Detects println statements that should be replaced with proper logging.
 *
 * This rule helps maintain code quality by flagging debug print statements
 * that often get left in production code.
 *
 * NOTE: Simple pattern matching on source text. More sophisticated
 * AST-based analysis could reduce false positives.
 */
class PrintlnDebugRule : AbstractDiagnosticRule() {

    private companion object {
        private val PRINTLN_PATTERN = Regex("""(^|\s)(println)\s*[(]""")
    }

    override val id = "println-debug"

    override val description = "Detect println statements that should use proper logging"

    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Information

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val lines = content.lines()

        lines.forEachIndexed { lineIndex, line ->
            // Match println calls (simple pattern)
            val match = PRINTLN_PATTERN.find(line)

            if (match != null) {
                val printlnGroup = match.groups[2] ?: return@forEachIndexed
                val startIndex = printlnGroup.range.first
                val endIndex = printlnGroup.range.last + 1

                diagnostics.add(
                    diagnostic(
                        lineIndex,
                        startIndex,
                        endIndex,
                        "Consider using a proper logger instead of println",
                        defaultSeverity,
                    ),
                )
            }
        }

        return diagnostics
    }
}
