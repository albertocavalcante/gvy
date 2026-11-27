// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JdkAtomicsTest {
    private val rule = JdkAtomics()

    @Test
    fun `flags every marked import and qualified use in the violations fixture`() {
        rule.assertReportsMarkedLines("jdk-atomics/violations.kt.fixture")
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        rule.assertReportsNothing("jdk-atomics/compliant.kt.fixture")
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

    @Test
    fun `a suppression silences only the declaration it annotates`() {
        val source = """
            class A {
                @Suppress("JdkAtomics") // A field updater needs the JDK type itself.
                val suppressed = java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater(A::class.java, "n")

                val other = java.util.concurrent.atomic.AtomicIntegerFieldUpdater.newUpdater(A::class.java, "n")
            }
        """.trimIndent()

        assertThat(rule.flaggedLines(source)).containsExactly(5)
    }

    @Test
    fun `a name split across lines is reported once, at its first line, by its type name`() {
        val source = "val holder = java.util.concurrent\n    .atomic\n    .AtomicReference(\"not started\")\n"
        val findings = rule.lint(source)
        assertThat(findings.map { it.entity.location.source.line }).containsExactly(1)
        val message = findings.single().message
        assertThat(message).startsWith("`java.util.concurrent.atomic.AtomicReference` bypasses ")
    }
}
