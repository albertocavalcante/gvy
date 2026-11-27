// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.test.lint
import io.gitlab.arturbosch.detekt.test.yamlConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GvyRuleSetProviderTest {
    /** What a rule must declare, and a snippet that it reports once. */
    private class Expected(val snippet: String, val severity: Severity, val debt: Debt)

    /** Every rule, by rule id, in the order the provider registers the rules. */
    private val expected = linkedMapOf(
        "RegexInFunctionBody" to Expected(
            "fun f() = Regex(\"a+\")",
            Severity.Performance,
            Debt.FIVE_MINS,
        ),
        "VolatileProperty" to Expected(
            "class A { @Volatile var flag = false }",
            Severity.Defect,
            Debt.TWENTY_MINS,
        ),
        "JdkAtomics" to Expected(
            "import java.util.concurrent.atomic.AtomicBoolean",
            Severity.Maintainability,
            Debt.TEN_MINS,
        ),
        "StdlibAtomics" to Expected(
            "import kotlin.concurrent.atomics.AtomicInt",
            Severity.Maintainability,
            Debt.TEN_MINS,
        ),
    )

    /** More findings, by rule id, whose messages are worded or assembled differently from the snippets above. */
    private val otherFindings = listOf(
        "RegexInFunctionBody" to "fun f() = \"a+\".toRegex()",
        "VolatileProperty" to "typealias V = kotlin.jvm.Volatile",
        "JdkAtomics" to "import java.util.concurrent.atomic.*",
        "JdkAtomics" to "val counter = java.util.concurrent.atomic.AtomicLong()",
        "StdlibAtomics" to "import kotlin.concurrent.atomics.*",
        "StdlibAtomics" to "@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)\nclass A",
    )

    /** Every rule, as the provider builds it from the `gvy` section of the YAML config at [resource]. */
    private fun rulesFrom(resource: String): List<Rule> =
        GvyRuleSetProvider().instance(yamlConfig(resource).subConfig("gvy")).rules.filterIsInstance<Rule>()

    @Test
    fun `provider registers every rule under the gvy id`() {
        val ruleSet = GvyRuleSetProvider().instance(Config.empty)
        assertThat(ruleSet.id).isEqualTo("gvy")
        assertThat(ruleSet.rules.map { it.ruleId }).containsExactlyElementsOf(expected.keys)
    }

    @Test
    fun `the plugin default config activates every rule`() {
        val rules = rulesFrom("config/config.yml")
        assertThat(rules.map { it.ruleId }).containsExactlyElementsOf(expected.keys)
        for (rule in rules) {
            assertThat(rule.active).describedAs(rule.ruleId).isTrue()
            assertThat(rule.lint(expected.getValue(rule.ruleId).snippet)).describedAs(rule.ruleId).hasSize(1)
        }
    }

    @Test
    fun `every rule declares the severity and debt the conventions give it`() {
        for (rule in rulesFrom("config/config.yml")) {
            val want = expected.getValue(rule.ruleId)
            assertThat(rule.issue.severity).describedAs(rule.ruleId).isEqualTo(want.severity)
            assertThat(rule.issue.debt).describedAs(rule.ruleId).isEqualTo(want.debt)
        }
    }

    @Test
    fun `every message and description follows the shared shape`() {
        val rules = rulesFrom("config/config.yml").associateBy { it.ruleId }
        val findings = expected.map { (ruleId, want) -> ruleId to want.snippet } + otherFindings
        for ((ruleId, snippet) in findings) {
            val message = rules.getValue(ruleId).lint(snippet).single().message
            assertThat(message).describedAs(snippet).matches(MESSAGE_SHAPE)
        }
        for (rule in rules.values) {
            assertThat(rule.issue.description).describedAs(rule.ruleId).startsWith("Reports ").endsWith(").")
        }
    }

    @Test
    fun `a yaml config can switch every rule off`() {
        val rules = rulesFrom("inactive-config.yml")
        assertThat(rules.map { it.ruleId }).containsExactlyElementsOf(expected.keys)
        for (rule in rules) {
            assertThat(rule.active).describedAs(rule.ruleId).isFalse()
            assertThat(rule.lint(expected.getValue(rule.ruleId).snippet)).describedAs(rule.ruleId).isEmpty()
        }
    }

    private companion object {
        /** "`code` what is wrong. The fix (#issue)." */
        const val MESSAGE_SHAPE = "`[^`]+` .+\\. .+ \\(#\\d+\\)\\."
    }
}
