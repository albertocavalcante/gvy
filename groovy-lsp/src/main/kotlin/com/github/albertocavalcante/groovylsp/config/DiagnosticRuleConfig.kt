package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticRule

/**
 * Configuration for custom diagnostic rules.
 *
 * Priority order:
 * 1. Disabled rule IDs (always disabled)
 * 2. Enabled rule IDs (override defaults and analysis type filters)
 * 3. Analysis type filters
 * 4. Rule's enabledByDefault flag
 */
data class DiagnosticRuleConfig(
    /**
     * Rule IDs to explicitly enable (even if disabled by default).
     */
    val enabledRuleIds: Set<String> = emptySet(),

    /**
     * Rule IDs to disable (never run).
     */
    val disabledRuleIds: Set<String> = emptySet(),

    /**
     * Analysis types allowed to run. Empty means all.
     */
    val enabledAnalysisTypes: Set<DiagnosticAnalysisType> = emptySet(),

    /**
     * Analysis types to disable (always skipped).
     */
    val disabledAnalysisTypes: Set<DiagnosticAnalysisType> = emptySet(),
) {
    fun isRuleEnabled(rule: DiagnosticRule): Boolean {
        if (rule.id in disabledRuleIds) return false
        if (rule.id in enabledRuleIds) return true
        if (rule.analysisType in disabledAnalysisTypes) return false
        if (enabledAnalysisTypes.isNotEmpty() && rule.analysisType !in enabledAnalysisTypes) return false
        return rule.enabledByDefault
    }
}
