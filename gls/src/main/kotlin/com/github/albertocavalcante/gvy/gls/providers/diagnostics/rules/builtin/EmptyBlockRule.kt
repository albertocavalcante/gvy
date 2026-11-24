// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
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

    private companion object {
        // Pattern: { } with only whitespace between
        private val EMPTY_BLOCK_PATTERN = Regex("""\{\s*}""")
    }

    override val id = "empty-block"

    override val description = "Detect empty code blocks that may indicate incomplete implementation"

    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Hint

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> =
        diagnosticsFromPattern(
            content = content,
            pattern = EMPTY_BLOCK_PATTERN,
            messageProvider = { "Empty block found - consider removing or adding implementation" },
            severity = defaultSeverity,
            excludeComments = true,
        )
}
