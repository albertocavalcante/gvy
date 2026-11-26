// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.gitlab.arturbosch.detekt.test.yamlConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdkAtomicsTest {
    private val rule = JdkAtomics()

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/jdk-atomics/$name")) {
        "missing fixture $name"
    }.readText()

    private fun flaggedLines(source: String): List<Int> =
        rule.lint(source).map { it.entity.location.source.line }.sorted()

    private fun markedLines(source: String): List<Int> =
        source.lines().withIndex().filter { (_, line) -> line.contains("// FLAG") }.map { (index, _) -> index + 1 }

    @Test
    fun `flags every marked import and qualified use in the violations fixture`() {
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
    fun `an import is reported once, not again for its path`() {
        assertThat(rule.lint("import java.util.concurrent.atomic.AtomicLong\n\nval x = 1")).hasSize(1)
    }

    @Test
    fun `message names the replacements`() {
        val findings = rule.lint("import java.util.concurrent.atomic.AtomicBoolean")
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("OnceFlag").contains("SharedValue").contains("Counter")
    }

    private fun ruleFrom(resource: String): JdkAtomics {
        val ruleSetConfig = yamlConfig(resource).subConfig("gvy")
        return GvyRuleSetProvider().instance(ruleSetConfig).rules.filterIsInstance<JdkAtomics>().single()
    }

    @Test
    fun `the plugin default config activates the rule`() {
        val rule = ruleFrom("config/config.yml")
        assertThat(rule.active).isTrue()
        assertThat(rule.lint("import java.util.concurrent.atomic.AtomicBoolean")).hasSize(1)
    }

    @Test
    fun `a yaml config can switch the rule off`() {
        val rule = ruleFrom("inactive-config.yml")
        assertThat(rule.active).isFalse()
        assertThat(rule.lint("import java.util.concurrent.atomic.AtomicBoolean")).isEmpty()
    }
}
