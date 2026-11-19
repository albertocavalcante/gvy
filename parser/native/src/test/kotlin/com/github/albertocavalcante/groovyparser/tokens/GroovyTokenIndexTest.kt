// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.tokens

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroovyTokenIndexTest {

    @Test
    fun `should detect line comments`() {
        val source = """
            def x = 1 // this is a comment
        """.trimIndent()
        val index = GroovyTokenIndex.build(source)

        val commentStart = source.indexOf("//")
        assertTrue(index.isInComment(commentStart), "At //")
        assertTrue(index.isInComment(commentStart + 5), "Middle of comment")
        assertFalse(index.isInComment(0), "Before comment")
    }

    @Test
    fun `should detect block comments`() {
        val source = """
            /* 
               multiline
               comment
            */
            def x = 1
        """.trimIndent()
        val index = GroovyTokenIndex.build(source)

        val commentStart = source.indexOf("/*")
        assertTrue(index.isInComment(commentStart), "At /*")
        assertTrue(index.isInComment(commentStart + 10), "Inside block comment")
        assertFalse(index.isInComment(source.indexOf("def")), "After block comment")
    }

    @Test
    fun `should detect single quoted strings`() {
        val source = "def s = 'hello world'"
        val index = GroovyTokenIndex.build(source)

        val stringStart = source.indexOf("'")
        assertTrue(index.isInString(stringStart), "At '")
        assertTrue(index.isInString(stringStart + 5), "Middle of string")
        assertFalse(index.isInString(0), "Before string")
    }

    @Test
    fun `should detect double quoted strings`() {
        val source = "def s = \"hello world\""
        val index = GroovyTokenIndex.build(source)

        val stringStart = source.indexOf("\"")
        assertFalse(index.isInString(0), "Before string")
        assertTrue(index.isInString(stringStart), "At \"")
        assertTrue(index.isInString(stringStart + 1), "Inside string")
        assertTrue(index.isInString(stringStart + 12), "At closing \"")
        assertFalse(index.isInString(stringStart + 13), "After string")
    }

    @Test
    fun `should detect triple quoted strings`() {
        val source = "def s = '''hello\nworld'''"
        val index = GroovyTokenIndex.build(source)

        val stringStart = source.indexOf("'''")
        assertTrue(index.isInString(stringStart), "At '''")
        assertTrue(index.isInString(stringStart + 10), "Inside multiline string")
    }

    @Test
    fun `should detect GStrings`() {
        val source = "def s = \"hello \${name}\""
        val index = GroovyTokenIndex.build(source)

        val gstringStart = source.indexOf("\"")
        assertTrue(index.isInString(gstringStart), "GStrings are considered strings")
        assertEquals(
            TokenContext.GString,
            index.contextAt(gstringStart + 1).fold({
                null
            }, { it }),
            "Context should be GString",
        )
    }

    @Test
    fun `should detect triple quoted GStrings`() {
        val source = "def s = \"\"\"hello \${name}\"\"\""
        val index = GroovyTokenIndex.build(source)

        // For triple-quoted GStrings, verify the string start is detected
        val gstringStart = source.indexOf("\"\"\"")
        assertTrue(index.isInString(gstringStart), "Triple-quoted GString start")
        // The 'h' after opening """ should be inside the string
        assertTrue(index.isInString(gstringStart + 3), "First character inside triple-quoted GString")
    }

    @Test
    fun `should detect shebang comments`() {
        val source = "#!/usr/bin/env groovy\ndef x = 1"
        val index = GroovyTokenIndex.build(source)

        assertTrue(index.isInComment(0), "Shebang start")
        assertTrue(index.isInComment(10), "Inside shebang")
        assertEquals(TokenContext.LineComment, index.contextAt(5).fold({ null }, { it }))
    }

    @Test
    fun `should handle empty source`() {
        val index = GroovyTokenIndex.build("")
        assertFalse(index.isInComment(0))
        assertFalse(index.isInString(0))
        assertEquals(TokenContext.Code, index.contextAt(0).fold({ TokenContext.Code }, { it }))
    }

    @Test
    fun `should handle edge cases and boundaries`() {
        val source = "// comment\ndef x = 's'"
        val index = GroovyTokenIndex.build(source)

        // Boundaries
        assertTrue(index.isInComment(source.indexOf("//")))
        assertTrue(index.isInComment(source.indexOf("\n") - 1))
        assertFalse(index.isInComment(source.indexOf("\n")))

        // Out of bounds
        assertFalse(index.isInComment(-1))
        assertFalse(index.isInComment(1000))

        // Combined check
        assertTrue(index.isInCommentOrString(5)) // in comment
        assertTrue(index.isInCommentOrString(source.indexOf("'") + 1)) // in string
        assertFalse(index.isInCommentOrString(source.indexOf("def"))) // in code
    }

    @Test
    fun `single quoted string boundaries use UTF16 offsets after and within supplementary text`() {
        val source = "def prior = '😀'; def value = 'later 😀';"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, "'later 😀'", TokenContext.StringLiteral)
    }

    @Test
    fun `double quoted string boundaries use UTF16 offsets after and within supplementary text`() {
        val source = "def prior = '😀'; def value = \"later 😀\";"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, "\"later 😀\"", TokenContext.StringLiteral)
    }

    @Test
    fun `triple quoted string boundaries preserve supplementary text and embedded CRLF`() {
        val source = "def prior = '😀'; def value = \"\"\"left 😀\r\nright\"\"\";"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, "\"\"\"left 😀\r\nright\"\"\"", TokenContext.StringLiteral)
    }

    @Test
    fun `line comment boundaries use UTF16 offsets and exclude following CRLF`() {
        val source = "def prior = '😀'; // later 😀\r\ndef next = 1"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, "// later 😀", TokenContext.LineComment)
        assertFalse(index.isInComment(source.indexOf('\n')))
        assertFalse(index.isInComment(source.indexOf("def next")))
    }

    @Test
    fun `block comment and subsequent string boundaries use UTF16 offsets`() {
        val source = "def prior = '😀'; /* later 😀 */ def value = 'after 😀';"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, "/* later 😀 */", TokenContext.BlockComment)
        assertContextSpan(source, index, "'after 😀'", TokenContext.StringLiteral)
    }

    @Test
    fun `GString segments use UTF16 offsets without coloring interpolation code`() {
        val source = "def prior = '😀'; def value = \"hello 😀\${name} bye 😀\";"
        val index = GroovyTokenIndex.build(source)

        // The lexer includes '$' in GStringBegin, then returns to code for the braces and expression.
        assertContextSpan(source, index, "\"hello 😀\$", TokenContext.GString)
        assertContextSpan(source, index, " bye 😀\"", TokenContext.GString)
        val interpolation = source.indexOf("name")
        for (offset in interpolation until interpolation + "name".length) {
            assertFalse(index.isInString(offset), "Interpolation UTF-16 offset $offset")
        }
    }

    @Test
    fun `supplementary shebang text does not shift a subsequent token across CRLF`() {
        val header = "#!/usr/bin/env groovy 😀\r"
        val source = "$header\ndef value = 'later 😀';"
        val index = GroovyTokenIndex.build(source)

        // The existing manual shebang span includes CR and stops before LF.
        assertContextSpan(source, index, header, TokenContext.LineComment)
        assertContextSpan(source, index, "'later 😀'", TokenContext.StringLiteral)
    }

    @Test
    fun `completed token boundaries remain correct before a malformed continuation`() {
        val source = "def prior = '😀'; def value = 'kept 😀'; // kept 😀\r\ndef broken = \"unterminated"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, "'kept 😀'", TokenContext.StringLiteral)
        assertContextSpan(source, index, "// kept 😀", TokenContext.LineComment)
        assertTrue(!index.complete, "Malformed continuation must not claim complete lexical coverage")
    }

    @Test
    fun `a supplementary string includes its closing quote and excludes the UTF16 end of source`() {
        val source = "'😀'"
        val index = GroovyTokenIndex.build(source)

        assertContextSpan(source, index, source, TokenContext.StringLiteral)
    }

    @Test
    fun `code queries require a covered code token in a complete lexer pass`() {
        val source = "println('text') // comment"
        val index = GroovyTokenIndex.build(source)

        assertTrue(index.hasCompleteLexicalCoverage)
        assertTrue(index.isCodeAt(source.indexOf("println")))
        assertTrue(index.isCodeAt(source.indexOf('(')))
        assertFalse(index.isCodeAt(source.indexOf("text")))
        assertFalse(index.isCodeAt(source.indexOf("comment")))
        assertFalse(index.isCodeAt(source.indexOf(' ')))
        assertFalse(index.isCodeAt(-1))
        assertFalse(index.isCodeAt(source.length))
        assertFalse(index.isCodeAt(source.length + 1))
    }

    @Test
    fun `incomplete lexer pass does not certify earlier code`() {
        val source = "println('ok'); def broken = \"unterminated"
        val index = GroovyTokenIndex.build(source)

        assertFalse(index.hasCompleteLexicalCoverage)
        assertFalse(index.isCodeAt(source.indexOf("println")))
        assertFalse(index.isCodeAt(source.indexOf('(')))
    }

    @Test
    fun `code offsets count supplementary characters as UTF-16`() {
        val source = "def text = '😀'; call()"
        val index = GroovyTokenIndex.build(source)

        assertTrue(index.hasCompleteLexicalCoverage)
        assertFalse(index.isCodeAt(source.indexOf("😀")))
        assertFalse(index.isCodeAt(source.indexOf("😀") + 1))
        assertTrue(index.isCodeAt(source.indexOf("call")))
        assertTrue(index.isCodeAt(source.indexOf('(')))
    }

    private fun assertContextSpan(source: String, index: GroovyTokenIndex, token: String, expected: TokenContext) {
        // Kotlin String indices and lengths supply independent UTF-16 expectations.
        val start = source.indexOf(token)
        assertTrue(start >= 0, "Expected fixture token must be present")
        val end = start + token.length

        assertEquals(null, index.contextAt(start - 1).fold({ null }, { it }), "Before UTF-16 start $start")
        for (offset in start until end) {
            assertEquals(expected, index.contextAt(offset).fold({ null }, { it }), "Inside UTF-16 span at $offset")
        }
        assertEquals(null, index.contextAt(end).fold({ null }, { it }), "At exclusive UTF-16 end $end")
    }
}
