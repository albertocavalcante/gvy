package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Detects empty code blocks that might indicate incomplete implementation.
 *
 * Empty blocks can be:
 * - Unfinished implementations (TODO markers)
 * - Dead code that should be removed
 * - Legitimate empty implementations (rare)
 *
 * NOTE: This is a simple pattern-based rule. More sophisticated analysis
 * using AST would provide better accuracy.
 */
class EmptyBlockRule : AbstractDiagnosticRule() {

    override val id = "empty-block"

    override val description = "Detect empty code blocks that may indicate incomplete implementation"

    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Hint

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val lines = content.lines()

        // Pattern: { } with only whitespace between
        val emptyBlockPattern = Regex("""\{\s*}""")

        lines.forEachIndexed { lineIndex, line ->
            var searchFrom = 0
            while (true) {
                val match = emptyBlockPattern.find(line, searchFrom) ?: break

                // Check if this is in a comment
                val beforeMatch = line.substring(0, match.range.first)
                if (!beforeMatch.contains("//")) {
                    diagnostics.add(
                        diagnostic(
                            lineIndex,
                            match.range.first,
                            match.range.last + 1,
                            "Empty block found - consider removing or adding implementation",
                            defaultSeverity,
                        ),
                    )
                }

                searchFrom = match.range.last + 1
            }
        }

        return diagnostics
    }
}
