// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StdlibAtomicsTest {
    private val rule = StdlibAtomics()

    @Test
    fun `flags every marked import and qualified use in the violations fixture`() {
        rule.assertReportsMarkedLines("stdlib-atomics/violations.kt.fixture")
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        rule.assertReportsNothing("stdlib-atomics/compliant.kt.fixture")
    }

    @Test
    fun `a star import is reported by its path`() {
        val findings = rule.lint("import kotlin.concurrent.atomics.*")
        assertThat(findings).hasSize(1)
        assertThat(findings.single().message).startsWith("`kotlin.concurrent.atomics.*` is part of the experimental ")
    }

    @Test
    fun `a qualified use and a qualified opt-in are reported in a file with no import`() {
        val source = """
            @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
            class A {
                val counter = kotlin.concurrent.atomics.AtomicInt(0)
            }
        """.trimIndent()

        assertThat(rule.flaggedLines(source)).containsExactly(1, 3)
    }

    @Test
    fun `a suppression silences only the declaration it annotates`() {
        val source = """
            class A {
                @Suppress("StdlibAtomics") // A documented exception.
                val suppressed = kotlin.concurrent.atomics.AtomicInt(0)

                val other = kotlin.concurrent.atomics.AtomicInt(0)
            }
        """.trimIndent()

        assertThat(rule.flaggedLines(source)).containsExactly(5)
    }

    @Test
    fun `message names the type and the replacements`() {
        val findings = rule.lint("val counter = kotlin.concurrent.atomics.AtomicInt(0)")
        assertThat(findings).hasSize(1)
        val message = findings.single().message
        assertThat(message).startsWith("`kotlin.concurrent.atomics.AtomicInt` is part of the experimental ")
        assertThat(message).contains("OnceFlag").contains("SharedValue").contains("Counter")
    }

    @Test
    fun `the opt-in marker gets a message about opting in, however it is named`() {
        val sources = listOf(
            "import kotlin.concurrent.atomics.ExperimentalAtomicApi",
            "@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)\nclass A",
            "@kotlin.concurrent.atomics.ExperimentalAtomicApi\nfun f() = Unit",
        )
        val expected = "`kotlin.concurrent.atomics.ExperimentalAtomicApi` opts into the experimental stdlib atomics"
        for (source in sources) {
            assertThat(rule.lint(source).single().message).describedAs(source).startsWith(expected)
        }
    }

    @Test
    fun `a file-wide suppression silences an import`() {
        val import = "import kotlin.concurrent.atomics.AtomicInt"
        assertThat(rule.lint(import)).hasSize(1)
        assertThat(rule.lint("@file:Suppress(\"StdlibAtomics\")\n\n$import")).isEmpty()
    }
}
