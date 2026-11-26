// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import io.gitlab.arturbosch.detekt.test.yamlConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VolatilePropertyTest {
    private val rule = VolatileProperty()

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/volatile-property/$name")) {
        "missing fixture $name"
    }.readText()

    private fun flaggedLines(source: String): List<Int> =
        rule.lint(source).map { it.entity.location.source.line }.sorted()

    private fun markedLines(source: String): List<Int> =
        source.lines().withIndex().filter { (_, line) -> line.contains("// FLAG") }.map { (index, _) -> index + 1 }

    @Test
    fun `flags every marked annotation in the violations fixture`() {
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
    fun `an alias of another annotation is not reported`() {
        val source = """
            import com.example.annotations.Volatile as Marker

            class A {
                @Marker var flag = false
            }
        """.trimIndent()

        assertThat(rule.lint(source)).isEmpty()
    }

    @Test
    fun `a file that imports another Volatile still reports Kotlin's by its qualified name`() {
        val source = """
            import com.example.annotations.Volatile

            class A {
                @Volatile var theirs = false
                @com.example.annotations.Volatile var theirsQualified = false
                @kotlin.jvm.Volatile var ours = false
            }
        """.trimIndent()

        assertThat(flaggedLines(source)).containsExactly(6)
    }

    @Test
    fun `explicit and star imports of Kotlin's annotation keep the plain name reported`() {
        val directives = listOf(
            "import kotlin.concurrent.Volatile",
            "import kotlin.concurrent.*",
            "import kotlin.jvm.*",
        )
        for (directive in directives) {
            val source = "$directive\n\nclass A {\n    @Volatile var flag = false\n}"

            assertThat(flaggedLines(source)).describedAs(directive).containsExactly(4)
        }
    }

    @Test
    fun `a use-site target on an annotation list is reported`() {
        assertThat(flaggedLines("class A {\n    @field:[Volatile] var flag = false\n}")).containsExactly(2)
    }

    @Test
    fun `a suppression silences only the field it annotates`() {
        val source = """
            class A {
                @Suppress("VolatileProperty") // Updated through an AtomicIntegerFieldUpdater.
                @Volatile var updated = 0

                @Volatile var other = 0
            }
        """.trimIndent()

        assertThat(flaggedLines(source)).containsExactly(5)
    }

    @Test
    fun `message names the replacements`() {
        val findings = rule.lint("class A { @Volatile var flag = false }")
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("OnceFlag").contains("SharedValue").contains("#2474")
    }

    private fun ruleFrom(resource: String): VolatileProperty {
        val ruleSetConfig = yamlConfig(resource).subConfig("gvy")
        return GvyRuleSetProvider().instance(ruleSetConfig).rules.filterIsInstance<VolatileProperty>().single()
    }

    @Test
    fun `the plugin default config activates the rule`() {
        val rule = ruleFrom("config/config.yml")
        assertThat(rule.active).isTrue()
        assertThat(rule.lint("class A { @Volatile var flag = false }")).hasSize(1)
    }

    @Test
    fun `a yaml config can switch the rule off`() {
        val rule = ruleFrom("inactive-config.yml")
        assertThat(rule.active).isFalse()
        assertThat(rule.lint("class A { @Volatile var flag = false }")).isEmpty()
    }
}
