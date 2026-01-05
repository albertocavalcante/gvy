package com.github.albertocavalcante.groovyparser.tokens

import kotlin.test.Test
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
}
