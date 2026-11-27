// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.test.lint
import io.gitlab.arturbosch.detekt.test.yamlConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GvyRuleSetProviderTest {
    /** A snippet that each rule reports once, by rule id, in the order the provider registers the rules. */
    private val snippets = linkedMapOf(
        "RegexInFunctionBody" to "fun f() = Regex(\"a+\")",
        "VolatileProperty" to "class A { @Volatile var flag = false }",
        "JdkAtomics" to "import java.util.concurrent.atomic.AtomicBoolean",
    )

    /** Every rule, as the provider builds it from the `gvy` section of the YAML config at [resource]. */
    private fun rulesFrom(resource: String): List<Rule> =
        GvyRuleSetProvider().instance(yamlConfig(resource).subConfig("gvy")).rules.filterIsInstance<Rule>()

    @Test
    fun `provider registers every rule under the gvy id`() {
        val ruleSet = GvyRuleSetProvider().instance(Config.empty)
        assertThat(ruleSet.id).isEqualTo("gvy")
        assertThat(ruleSet.rules.map { it.ruleId }).containsExactlyElementsOf(snippets.keys)
    }

    @Test
    fun `the plugin default config activates every rule`() {
        val rules = rulesFrom("config/config.yml")
        assertThat(rules.map { it.ruleId }).containsExactlyElementsOf(snippets.keys)
        for (rule in rules) {
            assertThat(rule.active).describedAs(rule.ruleId).isTrue()
            assertThat(rule.lint(snippets.getValue(rule.ruleId))).describedAs(rule.ruleId).hasSize(1)
        }
    }

    @Test
    fun `a yaml config can switch every rule off`() {
        val rules = rulesFrom("inactive-config.yml")
        assertThat(rules.map { it.ruleId }).containsExactlyElementsOf(snippets.keys)
        for (rule in rules) {
            assertThat(rule.active).describedAs(rule.ruleId).isFalse()
            assertThat(rule.lint(snippets.getValue(rule.ruleId))).describedAs(rule.ruleId).isEmpty()
        }
    }
}
