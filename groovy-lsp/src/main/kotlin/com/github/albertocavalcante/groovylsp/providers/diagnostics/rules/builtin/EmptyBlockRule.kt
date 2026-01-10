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
        // Pattern: { } with only whitespace between
        val emptyBlockPattern = Regex("""\{\s*}""")

        return diagnosticsFromPattern(
            content = content,
            pattern = emptyBlockPattern,
            messageProvider = { "Empty block found - consider removing or adding implementation" },
            severity = defaultSeverity,
            excludeComments = true,
        )
    }
}
