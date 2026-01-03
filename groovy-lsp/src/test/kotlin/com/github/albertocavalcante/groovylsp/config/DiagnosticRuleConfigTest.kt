package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRuleConfigTest {

    @Test
    fun `disabled rules override enabled rules`() {
        val config = DiagnosticRuleConfig(
            disabledRuleIds = setOf("rule-1"),
            enabledRuleIds = setOf("rule-1"),
        )
        val rule = testRule(id = "rule-1", enabledByDefault = true)

        assertFalse(config.isRuleEnabled(rule))
    }

    @Test
    fun `enabled rules override rule default disabled`() {
        val config = DiagnosticRuleConfig(
            enabledRuleIds = setOf("rule-1"),
        )
        val rule = testRule(id = "rule-1", enabledByDefault = false)

        assertTrue(config.isRuleEnabled(rule))
    }

    @Test
    fun `analysis type filter disables rule`() {
        val config = DiagnosticRuleConfig(
            enabledAnalysisTypes = setOf(DiagnosticAnalysisType.AST),
        )
        val rule = testRule(id = "rule-1", analysisType = DiagnosticAnalysisType.HEURISTIC)

        assertFalse(config.isRuleEnabled(rule))
    }

    @Test
    fun `explicitly enabled rule bypasses analysis type filter`() {
        val config = DiagnosticRuleConfig(
            enabledRuleIds = setOf("rule-1"),
            enabledAnalysisTypes = setOf(DiagnosticAnalysisType.AST),
        )
        val rule = testRule(id = "rule-1", enabledByDefault = false, analysisType = DiagnosticAnalysisType.HEURISTIC)

        assertTrue(config.isRuleEnabled(rule))
    }

    private fun testRule(
        id: String,
        enabledByDefault: Boolean,
        analysisType: DiagnosticAnalysisType = DiagnosticAnalysisType.HEURISTIC,
    ): DiagnosticRule = object : DiagnosticRule {
        override val id = id
        override val description = "Test rule"
        override val analysisType = analysisType
        override val defaultSeverity = DiagnosticSeverity.Warning
        override val enabledByDefault = enabledByDefault

        override suspend fun analyze(uri: URI, content: String, context: RuleContext): List<Diagnostic> = emptyList()
    }
}
