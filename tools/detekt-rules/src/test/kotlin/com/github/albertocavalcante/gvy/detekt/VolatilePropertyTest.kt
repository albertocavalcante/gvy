// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VolatilePropertyTest {
    private val rule = VolatileProperty()

    @Test
    fun `flags every marked annotation and typealias in the violations fixture`() {
        rule.assertReportsMarkedLines("volatile-property/violations.kt.fixture")
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        rule.assertReportsNothing("volatile-property/compliant.kt.fixture")
    }

    @Test
    fun `in a file that imports another Volatile, only Kotlin's qualified names are reported`() {
        rule.assertReportsMarkedLines("volatile-property/imports-other-volatile.kt.fixture")
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
    fun `another symbol imported as an escaped Volatile also takes the plain name`() {
        val source = "import com.example.Marker as `Volatile`\n\nclass A {\n    @Volatile var flag = false\n}"

        assertThat(rule.lint(source)).isEmpty()
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

            assertThat(rule.flaggedLines(source)).describedAs(directive).containsExactly(4)
        }
    }

    @Test
    fun `a use-site target on an annotation list is reported`() {
        assertThat(rule.flaggedLines("class A {\n    @field:[Volatile] var flag = false\n}")).containsExactly(2)
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

        assertThat(rule.flaggedLines(source)).containsExactly(5)
    }

    @Test
    fun `a typealias is reported at its name, with a message that names the alias`() {
        val findings = rule.lint("/** Docs. */\ntypealias V = kotlin.jvm.Volatile")
        assertThat(findings.map { it.entity.location.source.line }).containsExactly(2)
        assertThat(findings.single().message).startsWith("`typealias V = kotlin.jvm.Volatile` lets `@V` declare ")
    }

    @Test
    fun `message names the replacements`() {
        val findings = rule.lint("class A { @Volatile var flag = false }")
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).contains("OnceFlag").contains("SharedValue").contains("#2474")
    }
}
