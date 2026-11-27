// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import io.gitlab.arturbosch.detekt.test.lint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestFunctionReturnTypeTest {
    private val rule = TestFunctionReturnType()

    @Test
    fun `flags every marked function in the violations fixture`() {
        rule.assertReportsMarkedLines("test-function-return-type/violations.kt.fixture")
    }

    @Test
    fun `flags nothing in the compliant fixture`() {
        rule.assertReportsNothing("test-function-return-type/compliant.kt.fixture")
    }

    @Test
    fun `in a file that imports another Test, only JUnit's other names are reported`() {
        rule.assertReportsMarkedLines("test-function-return-type/other-test-annotation.kt.fixture")
    }

    @Test
    fun `a plain Test is not JUnit's in a file that imports no JUnit package`() {
        assertThat(rule.lint("class A {\n    @Test\n    fun x() = runBlocking { }\n}")).isEmpty()
    }

    @Test
    fun `every import that brings a JUnit name makes it reported`() {
        val functionsByImport = listOf(
            "import org.junit.jupiter.api.Test" to "@Test",
            "import org.junit.jupiter.api.*" to "@Test",
            "import org.junit.jupiter.api.*" to "@AfterAll",
            "import org.junit.jupiter.params.*" to "@ParameterizedTest",
            "import kotlin.test.Test" to "@Test",
            "import kotlin.test.*" to "@BeforeTest",
            "import org.junit.Test" to "@Test",
            "import org.junit.*" to "@BeforeClass",
            "import org.junit.Before as Setup" to "@Setup",
        )
        for ((directive, annotation) in functionsByImport) {
            val source = "$directive\n\nclass A {\n    $annotation\n    fun x() = runBlocking { }\n}"

            assertThat(rule.flaggedLines(source)).describedAs("$directive, $annotation").containsExactly(5)
        }
    }

    @Test
    fun `a name JUnit does not call a function for is not reported`() {
        val functionsByImport = listOf(
            "import org.junit.jupiter.api.TestFactory" to "@TestFactory",
            "import org.junit.jupiter.api.*" to "@TestFactory",
            "import org.junit.jupiter.api.*" to "@Disabled",
            "import org.junit.jupiter.params.*" to "@Test",
            "import kotlin.test.*" to "@BeforeEach",
            "import net.jqwik.api.Property" to "@Property",
            "import com.example.Test" to "@Test",
            "import com.example.Marker as Test" to "@Test",
            "import org.junit.jupiter.api.Test as Spec" to "@Test",
        )
        for ((directive, annotation) in functionsByImport) {
            val source = "$directive\n\nclass A {\n    $annotation\n    fun x() = runBlocking { }\n}"

            assertThat(rule.flaggedLines(source)).describedAs("$directive, $annotation").isEmpty()
        }
    }

    @Test
    fun `another annotation's explicit import takes the name from a star import`() {
        val source = """
            import com.example.BeforeEach
            import org.junit.jupiter.api.*

            class A {
                @BeforeEach
                fun other() = runBlocking { }

                @AfterEach
                fun junit() = runBlocking { }
            }
        """.trimIndent()

        assertThat(rule.flaggedLines(source)).containsExactly(9)
    }

    @Test
    fun `a suppression silences only the function it annotates`() {
        val source = """
            import org.junit.jupiter.api.Test

            class A {
                @Suppress("TestFunctionReturnType") // The contract test of a base class returns its subject.
                @Test
                fun suppressed() = runBlocking { }

                @Test
                fun other() = runBlocking { }
            }
        """.trimIndent()

        assertThat(rule.flaggedLines(source)).containsExactly(9)
    }

    @Test
    fun `an undeclared type is reported at the function's name, with the edit in the message`() {
        val message = messageFor("@Deprecated(\"old\")\n    @Test\n    fun `some test`() = runBlocking { }", line = 6)

        assertThat(message)
            .startsWith("`= runBlocking { }` is an expression body whose type this @Test function leaves to inference")
            .contains("JUnit does not accept a test or lifecycle function that returns a value")
            .contains("Insert `: Unit` between `)` and `=` and leave the body as it is")
            .endsWith("does not compile, use a block body instead (#2507).")
    }

    @Test
    fun `the message quotes the first line of the body, with its receiver and arguments`() {
        assertThat(messageFor("@Test\n    fun x() = rewriteRun(spec)")).startsWith("`= rewriteRun(spec)` is ")
        assertThat(messageFor("@Test\n    fun x() = scope.runTest(timeout) { }"))
            .startsWith("`= scope.runTest(timeout) { }` is ")
        assertThat(messageFor("@Test\n    fun x() =\n        runBlocking(Dispatchers.IO) {\n        }"))
            .startsWith("`= runBlocking(Dispatchers.IO) {` is ")
        assertThat(messageFor("@Test\n    fun x() = Unit")).startsWith("`= Unit` is ")
    }

    @Test
    fun `a long first line is cut, and backticks are dropped, so that the code span stays whole`() {
        val message = messageFor("@Test\n    fun x() = `a helper`(\"${"a".repeat(80)}\")")

        assertThat(message).startsWith("`= a helper(\"aaaa").contains("aaaa...` is an expression body")
        assertThat(message.substringBefore("` is ")).hasSize("`= ".length + 60)
    }

    @Test
    fun `the message names the annotation as the file spells it`() {
        val aliased = "import org.junit.jupiter.api.BeforeEach as Setup\n\nclass A {\n    @Setup\n    fun x() = f()\n}"
        val escaped = "import org.junit.jupiter.api.Test as `A Spec`\n\nclass A {\n    @`A Spec`\n    fun x() = f()\n}"

        assertThat(rule.lint(aliased).single().message).contains(" this @Setup function ")
        assertThat(rule.lint(escaped).single().message).contains(" this @`A Spec` function ")
    }

    @Test
    fun `another declared type is reported at the type, with the one spelling in the message`() {
        val message = messageFor("@Test\n    fun x(): kotlinx.coroutines.test.`TestResult` = runTest { }")

        assertThat(message)
            .startsWith("`: kotlinx.coroutines.test.TestResult` is not how a @Test function declares its return type")
            .endsWith("Declare exactly `: Unit` on an expression body, and no type on a block body (#2507).")
    }

    @Test
    fun `Unit without an expression body is reported as saying nothing, with the removal in the message`() {
        val bodies = listOf(" {\n    }", "")
        for (body in bodies) {
            val message = messageFor("@Test\n    fun x(): Unit$body")

            assertThat(message)
                .describedAs(body)
                .startsWith("`: Unit` says nothing on this @Test function, which has no expression body")
                .contains("Remove the type")
        }
    }

    @Test
    fun `Unit on a block body that only wraps a builder is left to CoroutineTestBody`() {
        val source = "import org.junit.jupiter.api.Test\n\nclass A {\n    @Test\n    fun x(): Unit {\n" +
            "        runBlocking { }\n    }\n}"

        assertThat(rule.lint(source)).isEmpty()
        assertThat(CoroutineTestBody().lint(source)).hasSize(1)
    }

    /** The message of the one finding in a class that imports JUnit's `Test` and declares [function] on line 4. */
    private fun messageFor(function: String, line: Int = 5): String {
        val finding = rule.lint("import org.junit.jupiter.api.Test\n\nclass A {\n    $function\n}").single()
        assertThat(finding.entity.location.source.line).isEqualTo(line)
        return finding.message
    }
}
