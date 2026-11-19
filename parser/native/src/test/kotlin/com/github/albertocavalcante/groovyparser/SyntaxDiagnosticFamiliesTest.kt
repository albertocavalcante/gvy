// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntaxDiagnosticFamiliesTest {
    private val parser = GroovyParserFacade()

    @ParameterizedTest
    @ValueSource(strings = [".", "?.", "*."])
    fun `unfinished member access underlines its operator`(operator: String) {
        assertDiagnostic(
            "person$operator",
            "Incomplete member access: expected a member name after '$operator'.",
            "incomplete-member-access",
            operator,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["=", "+", "-", "*", "/", "&&", "||"])
    fun `trailing operators explain the missing expression`(operator: String) {
        val prefix = if (operator == "=") "def count " else "def count = 1 "
        assertDiagnostic(
            prefix + operator,
            "Expected an expression after '$operator'.",
            "expected-expression",
            operator,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["class", "interface", "enum"])
    fun `unfinished declaration underlines its keyword`(keyword: String) {
        assertDiagnostic(
            keyword,
            "Incomplete declaration: expected a name after '$keyword'.",
            "incomplete-declaration",
            keyword,
        )
    }

    @Test
    fun `unfinished constructor expects a type`() {
        assertDiagnostic("new", "Expected a type after 'new'.", "expected-type", "new")
    }

    @Test
    fun `trait declaration missing its name keeps Groovy contextual keyword behavior`() {
        assertDiagnostic(
            "trait {",
            "Incomplete declaration: expected a name after 'trait'.",
            "incomplete-declaration",
            "trait",
        )
    }

    @ParameterizedTest
    @CsvSource("println(;(;)", "[1;[;]", "class A {;{;}", delimiter = ';')
    fun `unmatched opener explains its closing delimiter`(source: String, opener: String, closer: String) {
        assertDiagnostic(
            source,
            "Unclosed '$opener': expected '$closer' before end of file.",
            "unclosed-delimiter",
            opener,
        )
    }

    @ParameterizedTest
    @CsvSource("[1, 2);];)", "def x = (1];);]", "class A {];};]", delimiter = ';')
    fun `contradictory closer is underlined`(source: String, expected: String, found: String) {
        assertDiagnostic(
            source,
            "Mismatched closing delimiter: expected '$expected' but found '$found'.",
            "mismatched-delimiter",
            found,
        )
    }

    @Test
    fun `nested delimiters identify the unmatched opener reported by the compiler`() {
        assertDiagnostic(
            "println([1, 2",
            "Unclosed '(': expected ')' before end of file.",
            "unclosed-delimiter",
            "(",
        )
    }

    @Test
    fun `missing expression takes precedence over unmatched brace`() {
        assertDiagnostic("class A { def count =", "Expected an expression after '='.", "expected-expression", "=")
    }

    @Test
    fun `a rejected next line stays attached to the immediately preceding operator`() {
        val diagnostic = syntaxErrors("def x = 1 +\ndef y = 2").first()
        assertEquals("Expected an expression after '+'.", diagnostic.message)
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(10, diagnostic.range.start.character)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "def x =\n1",
            "def x = 1 +\n2",
            "person.\ntoString()",
            "person?.\ntoString()",
            "[1]*.\ntoString()",
            "class A {}",
            "interface A {}",
            "trait A {}",
            "enum A { X }",
            "new String()",
            "println(1)",
            "[1, 2]",
            "def text = 'person. = ( ['",
            "def text = \"person. = ( [\"",
            "def pattern = /person. = ( [ /",
            "def pattern = $/person. = ( [ /$",
            "// person. = ( [\nprintln 1",
            "/* person. = ( [ */ println 1",
            "def text = \"hello \${1 + 2} ( [\"",
            "trait",
        ],
    )
    fun `valid repairs multiline continuations and literal punctuation stay valid`(source: String) {
        assertTrue(syntaxErrors(source).isEmpty(), source)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "person.\nprintln(,)", "def x =\nprintln(,)", "println(1,,2)", "=", "def x = =", "def x = *", "println(1]",
        ],
    )
    fun `unrelated errors retain compiler detail`(source: String) {
        assertTrue(syntaxErrors(source).all { it.code == "syntax-error" }, source)
    }

    @ParameterizedTest
    @ValueSource(strings = ["def text = \"person.", "def pattern = /person.", "/* person.", "def text = \"\${person."])
    fun `incomplete lexical coverage preserves the compiler diagnostic`(source: String) {
        assertTrue(syntaxErrors(source).all { it.code == "syntax-error" }, source)
    }

    @Test
    fun `member range uses UTF16 columns after supplementary characters`() {
        val source = "println \"😀\"; person."
        val diagnostic = syntaxErrors(source).first()
        assertEquals("incomplete-member-access", diagnostic.code)
        assertEquals(source.length - 1, diagnostic.range.start.character)
        assertEquals(source.length, diagnostic.range.end.character)
    }

    @Test
    fun `CRLF and EOF never push ranges outside the document`() {
        val source = "// 😀\r\nperson?.\r\n"
        val diagnostic = syntaxErrors(source).first()
        assertEquals("incomplete-member-access", diagnostic.code)
        assertEquals(1, diagnostic.range.start.line)
        assertEquals(6, diagnostic.range.start.character)
        assertEquals(8, diagnostic.range.end.character)
    }

    @Test
    fun `completed GStrings do not hide unrelated incomplete syntax`() {
        val diagnostic = syntaxErrors("def text = \"\${1} ([\"\nperson.").first()
        assertEquals("incomplete-member-access", diagnostic.code)
        assertEquals(1, diagnostic.range.start.line)
    }

    @ParameterizedTest
    @ValueSource(strings = ["person. // unfinished member", "person. /* comment */", "person.\n// comment"])
    fun `trailing comments remain trivia for an incomplete member`(source: String) {
        val diagnostic = syntaxErrors(source).first()
        assertEquals("incomplete-member-access", diagnostic.code)
        assertEquals(6, diagnostic.range.start.character)
        assertEquals(7, diagnostic.range.end.character)
    }

    private fun syntaxErrors(source: String): List<ParserDiagnostic> = parser.parse(
        ParseRequest(uri = URI.create("file:///SyntaxFamilies.groovy"), content = source),
    ).diagnostics.filter { it.severity == ParserSeverity.ERROR }

    private fun assertDiagnostic(source: String, message: String, code: String, underlined: String) {
        val diagnostic = syntaxErrors(source).first()
        assertEquals(message, diagnostic.message, source)
        assertEquals(code, diagnostic.code, source)
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.end.line)
        assertEquals(underlined, source.substring(diagnostic.range.start.character, diagnostic.range.end.character))
    }
}
