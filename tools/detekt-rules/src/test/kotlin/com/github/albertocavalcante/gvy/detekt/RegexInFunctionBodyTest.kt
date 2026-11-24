// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegexInFunctionBodyTest {
    private val rule = RegexInFunctionBody()

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/regex-in-function-body/$name")) {
        "missing fixture $name"
    }.readText()

    private fun flaggedLines(source: String): List<Int> =
        rule.lint(source).map { it.entity.location.source.line }.sorted()

    private fun markedLines(source: String): List<Int> =
        source.lines().withIndex().filter { (_, line) -> line.contains("// FLAG") }.map { (index, _) -> index + 1 }

    @Test
    fun `flags every marked construction in the violations fixture`() {
        val source = fixture("violations.kt.fixture")
        assertThat(markedLines(source)).isNotEmpty()
        assertThat(flaggedLines(source)).isEqualTo(markedLines(source))
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        val source = fixture("compliant.kt.fixture")
        assertThat(markedLines(source)).isEmpty()
        assertThat(flaggedLines(source)).isEmpty()
    }

    @Test
    fun `message names the construction and the fix`() {
        val findings = rule.lint("fun f() = Regex(\"a+\")")
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("Regex").contains("hoist")
    }

    @Test
    fun `provider registers the rule under the gvy id`() {
        val ruleSet = GvyRuleSetProvider().instance(io.gitlab.arturbosch.detekt.api.Config.empty)
        assertThat(ruleSet.id).isEqualTo("gvy")
        assertThat(ruleSet.rules.map { it.ruleId }).containsExactly("RegexInFunctionBody")
    }
}
