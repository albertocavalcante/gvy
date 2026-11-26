// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.java.tree.J
import org.openrewrite.tree.ParseError

/** Never-merge raw SDK probe; no gvy formatter or shebang/restoration path participates. */
class RawRewriteCandidateProbeTest {
    @TestFactory
    fun `raw edit preserves expression text and is idempotent`(): List<DynamicTest> = listOf(
        "~a",
        "-x as int",
        "-x",
        "+x",
        "-(x + 1)",
        "-foo()",
        "3.303e+23",
        "[a: 1, *:m]",
    ).map { expression ->
        dynamicTest(expression) {
            val input = "class Probe { def before() { return $expression } }\n"
            val expected = "class Probe { def after() { return $expression } }\n"
            assertRawEdit(input, expected)
        }
    }

    @TestFactory
    fun `raw shebang with script preserves bytes through edit`(): List<DynamicTest> = listOf(
        "LF" to "\n",
        "CRLF" to "\r\n",
    ).map { (label, newline) ->
        dynamicTest(label) {
            val input = "#!/usr/bin/env groovy${newline}class Probe { def before() { return 1 } }$newline"
            val expected = "#!/usr/bin/env groovy${newline}class Probe { def after() { return 1 } }$newline"
            assertRawEdit(input, expected)
        }
    }

    @Test
    fun `raw shebang-only without terminal newline round trips`() {
        val input = "#!/usr/bin/env groovy"
        assertThat(parse(input).printAll()).isEqualTo(input)
    }

    private fun assertRawEdit(input: String, expected: String) {
        val original = parse(input)
        assertThat(original.printAll()).describedAs("initial raw parse round trip").isEqualTo(input)
        val first = rename(original)
        assertThat(first.second).describedAs("first visitor must edit exactly one method").isEqualTo(1)
        val edited = first.first.printAll()
        assertThat(edited).describedAs("raw edit must preserve all other bytes").isEqualTo(expected)
        assertThat(edited).isNotEqualTo(input)
        val reparsed = parse(edited)
        assertThat(reparsed.printAll()).describedAs("edited raw parse round trip").isEqualTo(expected)
        val second = rename(reparsed)
        assertThat(second.second).describedAs("second visitor must make no edit").isEqualTo(0)
        assertThat(second.first.printAll()).describedAs("second pass must be unchanged").isEqualTo(expected)
    }

    private fun parse(text: String): SourceFile {
        val context = InMemoryExecutionContext { throw it }
        context.putMessage(ExecutionContext.REQUIRE_PRINT_EQUALS_INPUT, false)
        val sources = GroovyParser.builder().build().parse(context, text).use { it.toList() }
        assertThat(sources).hasSize(1)
        val source = sources.single()
        assertThat(source).describedAs("raw parser must return a syntax tree").isNotInstanceOf(ParseError::class.java)
        return source
    }

    private fun rename(source: SourceFile): Pair<SourceFile, Int> {
        var changed = 0
        val visitor = object : GroovyIsoVisitor<Unit>() {
            override fun visitMethodDeclaration(method: J.MethodDeclaration, p: Unit): J.MethodDeclaration {
                val visited = super.visitMethodDeclaration(method, p)
                if (visited.name.simpleName != "before") return visited
                changed++
                return visited.withName(visited.name.withSimpleName("after"))
            }
        }
        return (visitor.visit(source, Unit) as SourceFile) to changed
    }
}
