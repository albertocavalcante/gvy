// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoroutineTestBodyTest {
    private val rule = CoroutineTestBody()

    @Test
    fun `flags every marked builder in the violations fixture`() {
        rule.assertReportsMarkedLines("coroutine-test-body/violations.kt.fixture")
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        rule.assertReportsNothing("coroutine-test-body/compliant.kt.fixture")
    }

    @Test
    fun `a plain Test is not JUnit's in a file that imports no JUnit package`() {
        assertThat(rule.lint("class A {\n    @Test\n    fun x() {\n        runBlocking { }\n    }\n}")).isEmpty()
    }

    @Test
    fun `a block wrapper with a type argument is reported once, as a wrapper`() {
        val findings = rule.lint(classWith("@Test\n    fun x() {\n        runBlocking<Unit> { }\n    }"))

        assertThat(findings.single().message).startsWith("`runBlocking<Unit> { }` is the only statement ")
    }

    @Test
    fun `a suppression silences only the function it annotates`() {
        val source = classWith(
            """
            @Suppress("CoroutineTestBody") // Mirrors the shape of the generated client under test.
                @Test
                fun suppressed() {
                    runBlocking { }
                }

                @Test
                fun other() {
                    runBlocking { }
                }
            """.trimIndent(),
        )

        assertThat(rule.flaggedLines(source)).containsExactly(12)
    }

    @Test
    fun `a type argument is reported at the argument, with the one form in the message`() {
        val finding = rule.lint(classWith("@Test\n    fun x(): Unit =\n        runBlocking< Unit > { }")).single()

        assertThat(finding.entity.location.source.line).isEqualTo(6)
        assertThat(finding.message)
            .startsWith("`runBlocking<Unit>` puts the return type of this @Test function on the coroutine builder")
            .contains("Remove the type argument and declare `: Unit` on the function, between `)` and `=`")
            .endsWith("keep the builder's receiver and arguments (#2507).")
    }

    @Test
    fun `a block wrapper is reported at its statement, quoted with its receiver and arguments`() {
        val function = "@Test\n    fun x() {\n        return scope.runTest(timeout = limit) {\n        }\n    }"
        val finding = rule.lint(classWith(function)).single()

        assertThat(finding.entity.location.source.line).isEqualTo(6)
        assertThat(finding.message)
            .startsWith("`return scope.runTest(timeout = limit) {` is the only statement in the block body of ")
            .contains(" of this @Test function. ")
            .contains("Write `fun name(): Unit =` followed by that call, with its receiver and arguments")
            .endsWith("remove the outer braces and any `return` (#2507).")
    }

    @Test
    fun `a builder behind a safe call is not reported, because Unit cannot be its type`() {
        assertThat(rule.lint(classWith("@Test\n    fun x() {\n        scope?.runTest { }\n    }"))).isEmpty()
        assertThat(rule.lint(classWith("@Test\n    fun x() = scope?.runTest<Unit> { }"))).isEmpty()
    }

    /** A class that imports JUnit's `Test` and declares [function] from line 4. */
    private fun classWith(function: String): String = "import org.junit.jupiter.api.Test\n\nclass A {\n    $function\n}"
}
