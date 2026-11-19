// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntaxDiagnosticTest {
    private fun diagnostic(source: String) = GroovyParserFacade().parse(
        ParseRequest(uri = URI.create("file:///Test.groovy"), content = source),
    ).diagnostics.first()

    @Test
    fun `incomplete imports and packages identify the missing name`() {
        val examples = listOf(
            "import" to "Incomplete import: expected an import name.",
            "import /* comment */" to "Incomplete import: expected an import name.",
            "import static" to "Incomplete import: expected an import name.",
            "import groovy.transform.\n" to "Incomplete import: expected a name or '*' after '.'.",
            "import java.util.List as" to "Incomplete import: expected an alias after 'as'.",
            "import /* comment\n continued */ demo." to "Incomplete import: expected a name or '*' after '.'.",
            "import demo.\\\nName." to "Incomplete import: expected a name or '*' after '.'.",
            "package" to "Incomplete package declaration: expected a package name.",
            "package demo." to "Incomplete package declaration: expected a name after '.'.",
        )
        for ((source, expected) in examples) {
            val diagnostic = diagnostic(source)
            assertEquals(expected, diagnostic.message, source)
            assertTrue(diagnostic.code != "syntax-error", source)
        }
    }

    @Test
    fun `later GStrings do not hide an incomplete import`() {
        val diagnostic = diagnostic("import demo.\nprintln(\"$" + "name\")")
        assertEquals("incomplete-import", diagnostic.code)
    }

    @Test
    fun `import text inside interpolation is not treated as a declaration`() {
        val diagnostic = diagnostic("println(\"$" + "{\nimport demo.\n}\")")
        assertEquals("syntax-error", diagnostic.code)
    }

    @Test
    fun `trailing dot range uses UTF-16 columns with CRLF`() {
        val diagnostic = diagnostic("// 😀\r\nimport groovy.transform.\r\n")
        assertEquals("Incomplete import: expected a name or '*' after '.'.", diagnostic.message)
        assertEquals(1, diagnostic.range.start.line)
        assertEquals(23, diagnostic.range.start.character)
        assertEquals(24, diagnostic.range.end.character)
    }

    @Test
    fun `an unrelated error does not reuse a preceding import`() {
        val diagnostic = diagnostic("import java.util.List\nprintln(]\n")
        assertEquals("mismatched-delimiter", diagnostic.code)
    }

    @Test
    fun `malformed import paths keep the compiler diagnostic`() {
        for (source in listOf("import demo + .", "import demo..", "import demo. as")) {
            val result = GroovyParserFacade().parse(ParseRequest(URI.create("file:///Test.groovy"), source))
            assertTrue(result.diagnostics.none { it.code == "incomplete-import" }, source)
        }
    }

    @Test
    fun `valid imports do not produce incomplete diagnostics`() {
        for (source in listOf("import java.util.List as Things", "import static java.lang.Math.*")) {
            val result = GroovyParserFacade().parse(ParseRequest(URI.create("file:///Test.groovy"), source))
            assertTrue(result.diagnostics.none { it.code == "incomplete-import" }, source)
        }
    }

    @Test
    fun `expression coordinates count supplementary characters as UTF-16`() {
        val source = "'😀'; def x = )"
        val diagnostic = diagnostic(source)
        assertEquals(source.indexOf('='), diagnostic.range.start.character)
        assertTrue(diagnostic.range.end.character <= source.length)
    }

    @Test
    fun `incomplete lexer coverage does not borrow an import`() {
        val other = diagnostic("def x = 'unterminated import demo.")
        assertEquals("syntax-error", other.code)
    }

    @Test
    fun `fallback messages do not duplicate compiler coordinates`() {
        val diagnostic = diagnostic("println(1,,2)")
        assertEquals("syntax-error", diagnostic.code)
        assertTrue(!diagnostic.message.contains(" @ line "), diagnostic.message)
    }
}
