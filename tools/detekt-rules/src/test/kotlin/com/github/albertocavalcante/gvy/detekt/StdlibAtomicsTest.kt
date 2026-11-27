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
        assertThat(findings.single().message).startsWith("`kotlin.concurrent.atomics.*` is experimental")
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
        assertThat(message).startsWith("`kotlin.concurrent.atomics.AtomicInt` is experimental")
        assertThat(message).contains("OnceFlag").contains("SharedValue").contains("Counter")
    }
}
