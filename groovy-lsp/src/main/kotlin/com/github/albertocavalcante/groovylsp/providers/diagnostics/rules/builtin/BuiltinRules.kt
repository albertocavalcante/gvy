package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticRule

/**
 * Factory for creating built-in diagnostic rules.
 *
 * Provides convenient access to all built-in rules and allows for
 * easy registration of the complete rule set.
 */
object BuiltinRules {

    /**
     * Get all built-in rules.
     *
     * NOTE: Some rules are disabled by default (e.g., NullSafetyRule)
     * and need to be explicitly enabled via configuration.
     */
    fun getAllRules(): List<DiagnosticRule> = listOf(
        PrintlnDebugRule(),
        EmptyBlockRule(),
        JenkinsPipelineStageRule(),
        SpockTestStructureRule(),
        NullSafetyRule(),
    )

    /**
     * Get only rules that are enabled by default.
     */
    fun getDefaultRules(): List<DiagnosticRule> = getAllRules().filter { it.enabledByDefault }

    /**
     * Get rules for a specific context (e.g., Jenkins, Spock).
     */
    fun getRulesForContext(context: RuleContext): List<DiagnosticRule> = when (context) {
        RuleContext.JENKINS -> listOf(
            JenkinsPipelineStageRule(),
            PrintlnDebugRule(),
            EmptyBlockRule(),
        )

        RuleContext.SPOCK -> listOf(
            SpockTestStructureRule(),
            EmptyBlockRule(),
        )

        RuleContext.GENERAL -> listOf(
            PrintlnDebugRule(),
            EmptyBlockRule(),
            NullSafetyRule(),
        )
    }

    /**
     * Context for rule selection.
     */
    enum class RuleContext {
        JENKINS,
        SPOCK,
        GENERAL,
    }
}
