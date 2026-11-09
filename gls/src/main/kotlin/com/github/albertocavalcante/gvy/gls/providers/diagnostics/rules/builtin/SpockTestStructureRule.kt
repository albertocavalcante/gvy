package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Retains the registered Spock rule and its configuration without speculative reports.
 *
 * The former preferred-block template was not a Spock validity rule: minimal features and helpers
 * can legitimately omit when/then/expect blocks. Replacing its text scan with AST checks would
 * preserve that invalid premise. Keep this entry quiet until a separate, proven invariant exists;
 * parsing work that cannot establish a diagnostic would add no value here.
 */
class SpockTestStructureRule : AbstractDiagnosticRule() {
    override val id = "spock-test-structure"

    override val description = "Reserved Spock structure rule; no standalone diagnostics"

    // Preserve existing rule metadata and settings compatibility while the old reports are retired.
    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Information

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> = emptyList()
}
