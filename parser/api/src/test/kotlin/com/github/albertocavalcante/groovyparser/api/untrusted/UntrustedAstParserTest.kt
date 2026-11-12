// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.api.untrusted

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UntrustedAstParserTest {
    private fun rejected(source: String, limits: UntrustedParseLimits = UntrustedParseLimits()) =
        assertIs<UntrustedParseResult.Rejected>(UntrustedAstParser.parse(source, "test", limits))

    @Test
    fun `parses declarative text`() {
        val result = UntrustedAstParser.parse("ruleset { description 'x' }", "test")

        assertIs<UntrustedParseResult.Parsed>(result)
    }

    @Test
    fun `never runs phase operations or transforms while parsing`() {
        val marker = File.createTempFile("untrusted", ".marker").also { it.delete() }
        val path = marker.path.replace("\\", "/")
        val source = """
            @groovy.transform.ASTTest(value={ new File('$path').text = 'x' })
            @Grab('org.apache.commons:commons-lang3:3.14.0')
            class A {}
            new File('$path').text = 'y'
        """.trimIndent()

        UntrustedAstParser.parse(source, "test")

        assertFalse(marker.exists(), "parsing executed code")
    }

    @Test
    fun `rejects text over the size cap before parsing`() {
        assertTrue(rejected("x".repeat(100), UntrustedParseLimits(maxChars = 10)).reason.contains("larger"))
    }

    @Test
    fun `bounds nesting before the parser sees the text and does not hang`() {
        val deep = "ruleset { " + "x { ".repeat(5_000) + "}".repeat(5_000) + " }"

        assertTrue(rejected(deep).reason.contains("nested"))
    }

    @Test
    fun `brackets inside strings and comments do not count as nesting`() {
        val source = "ruleset { description 'a {{{{{{{{{{{{ (((( [[[[[' // {{{{{{{{{{\n /* {{{{{{{{{{ */ }"

        assertIs<UntrustedParseResult.Parsed>(
            UntrustedAstParser.parse(source, "test", UntrustedParseLimits(maxNesting = 2)),
        )
    }

    @Test
    fun `counts statements recursively including closures`() {
        val source = "contributor { " + "method(name: 'x')\n".repeat(30) + " }"

        val result = rejected(source, UntrustedParseLimits(maxStatements = 20))

        assertTrue(result.reason.contains("statements"), result.reason)
    }

    @Test
    fun `reports the position of a syntax error`() {
        val result = rejected("ruleset {\n  TrailingWhitespace )\n}")

        assertNotNull(result.line)
        assertTrue(result.reason.startsWith("not parseable"))
    }

    @Test
    fun `limits are parameters`() {
        val big = "ruleset { description '" + "y".repeat(100_000) + "' }"

        assertIs<UntrustedParseResult.Rejected>(UntrustedAstParser.parse(big, "t"))
        assertEquals(
            true,
            UntrustedAstParser.parse(big, "t", UntrustedParseLimits(maxChars = 200_000)) is UntrustedParseResult.Parsed,
        )
    }

    private fun assertBoundedQuickly(label: String, source: String) {
        val started = System.nanoTime()
        val result = rejected(source)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            result.reason.startsWith("nested deeper"),
            "$label: expected the nesting cap, got '${result.reason}'",
        )
        assertTrue(millis < 5_000, "$label took $millis ms")
    }

    @Test
    fun `nesting inside GString interpolation is counted`() {
        assertBoundedQuickly("gstring", "x = \"\${" + "(".repeat(1000) + ")".repeat(1000) + "}\"")
        val tripleGString = "x = \"\"\"\${" + "{->".repeat(500) + "}".repeat(500) + "}\"\"\""
        assertBoundedQuickly("triple gstring", tripleGString)
    }

    @Test
    fun `a quote inside a slashy string does not hide the nesting that follows`() {
        assertBoundedQuickly("slashy", "x = /'/; [" + "[".repeat(1000) + "]".repeat(1000) + "]")
        assertBoundedQuickly("dollar slashy", "x = \$/'/\$; [" + "[".repeat(1000) + "]".repeat(1000) + "]")
    }

    @Test
    fun `many moderately nested blocks stay cheap`() {
        val block = "a" + "{ ".repeat(8) + "}".repeat(8) + "\n"
        val source = "ruleset {\n" + block.repeat(500) + "}"

        val started = System.nanoTime()
        UntrustedAstParser.parse(source, "t", UntrustedParseLimits(maxChars = 200_000, maxStatements = 100_000))

        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test
    fun `safe-index openers count as nesting`() {
        assertBoundedQuickly("safe index", "ruleset { x = a" + "?[a".repeat(900) + "]".repeat(900) + " }")
    }

    @Test
    fun `brackets after a GString interpolation do not leak nesting`() {
        val source = "ruleset { description \"" + "\${a}".repeat(200) + "\" }"

        val limits = UntrustedParseLimits(maxNesting = 3)

        assertIs<UntrustedParseResult.Parsed>(UntrustedAstParser.parse(source, "test", limits))
    }

    @Test
    fun `lexer errors are rejected with a position instead of escaping`() {
        for ((label, source) in mapOf(
            "unterminated string" to "ruleset { description \"abc }",
            "unterminated comment" to "ruleset { /* never closed",
            "nul character" to "ruleset { \u0000 }",
            "hash" to "ruleset { # }",
        )) {
            val result = rejected(source)

            assertTrue(result.reason.isNotBlank(), "$label must be rejected with a reason")
        }
        assertNotNull(rejected("ruleset {\n description \"abc }").line)
    }
}
