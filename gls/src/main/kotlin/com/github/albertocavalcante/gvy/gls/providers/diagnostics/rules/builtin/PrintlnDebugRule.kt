package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
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
        // Use findPatternMatches helper for more control over match processing
        return findPatternMatches(
            content = content,
            pattern = PRINTLN_PATTERN,
            excludeComments = true,
        ).mapNotNull { lineMatch ->
            // Extract the "println" group from the match
            val printlnGroup = lineMatch.match.groups[2] ?: return@mapNotNull null

            diagnostic(
                lineMatch.lineIndex,
                printlnGroup.range.first,
                printlnGroup.range.last + 1,
                "Consider using a proper logger instead of println",
                defaultSeverity,
            )
        }.toList()
    }
}
