package com.github.albertocavalcante.groovyparser.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringEscapeUtilsTest {

    @Test
    fun `escapeGroovy escapes special characters`() {
        assertEquals("\\\\", StringEscapeUtils.escapeGroovy("\\"))
        assertEquals("\\'", StringEscapeUtils.escapeGroovy("'"))
        assertEquals("\\n", StringEscapeUtils.escapeGroovy("\n"))
        assertEquals("\\r", StringEscapeUtils.escapeGroovy("\r"))
        assertEquals("\\t", StringEscapeUtils.escapeGroovy("\t"))
    }

    @Test
    fun `escapeGroovy escapes unicode characters`() {
        assertEquals("\\u0000", StringEscapeUtils.escapeGroovy("\u0000"))
        assertEquals("\\u001f", StringEscapeUtils.escapeGroovy("\u001F"))
    }

    @Test
    fun `escapeGroovy leaves normal characters unchanged`() {
        assertEquals("Hello World", StringEscapeUtils.escapeGroovy("Hello World"))
        assertEquals("123abc", StringEscapeUtils.escapeGroovy("123abc"))
    }

    @Test
    fun `escapeGString escapes dollar sign`() {
        assertEquals("\\${'$'}variable", StringEscapeUtils.escapeGString("${'$'}variable"))
        assertEquals("\\\"", StringEscapeUtils.escapeGString("\""))
    }

    @Test
    fun `unescapeGroovy handles common escape sequences`() {
        assertEquals("\n", StringEscapeUtils.unescapeGroovy("\\n"))
        assertEquals("\r", StringEscapeUtils.unescapeGroovy("\\r"))
        assertEquals("\t", StringEscapeUtils.unescapeGroovy("\\t"))
        assertEquals("\\", StringEscapeUtils.unescapeGroovy("\\\\"))
        assertEquals("'", StringEscapeUtils.unescapeGroovy("\\'"))
        assertEquals("\"", StringEscapeUtils.unescapeGroovy("\\\""))
        assertEquals("\$", StringEscapeUtils.unescapeGroovy("\\\$"))
    }

    @Test
    fun `unescapeGroovy handles unicode escapes`() {
        assertEquals("A", StringEscapeUtils.unescapeGroovy("\\u0041"))
        assertEquals("€", StringEscapeUtils.unescapeGroovy("\\u20ac"))
    }

    @Test
    fun `escapeRegex escapes regex metacharacters`() {
        assertEquals("\\.", StringEscapeUtils.escapeRegex("."))
        assertEquals("\\*", StringEscapeUtils.escapeRegex("*"))
        assertEquals("\\+", StringEscapeUtils.escapeRegex("+"))
        assertEquals("\\[\\]", StringEscapeUtils.escapeRegex("[]"))
        assertEquals("\\(\\)", StringEscapeUtils.escapeRegex("()"))
    }

    @Test
    fun `toIdentifier converts strings to valid identifiers`() {
        assertEquals("hello", StringEscapeUtils.toIdentifier("hello"))
        assertEquals("_123", StringEscapeUtils.toIdentifier("123"))
        assertEquals("hello_world", StringEscapeUtils.toIdentifier("hello-world"))
        assertEquals("_", StringEscapeUtils.toIdentifier(""))
    }

    @Test
    fun `isValidIdentifier checks identifier validity`() {
        assertTrue(StringEscapeUtils.isValidIdentifier("hello"))
        assertTrue(StringEscapeUtils.isValidIdentifier("helloWorld"))
        assertTrue(StringEscapeUtils.isValidIdentifier("_private"))
        assertTrue(StringEscapeUtils.isValidIdentifier("var123"))

        assertFalse(StringEscapeUtils.isValidIdentifier(""))
        assertFalse(StringEscapeUtils.isValidIdentifier("123abc"))
        assertFalse(StringEscapeUtils.isValidIdentifier("hello-world"))
    }

    @Test
    fun `suggestQuoteStyle recommends appropriate style`() {
        assertEquals(
            StringEscapeUtils.QuoteStyle.SINGLE,
            StringEscapeUtils.suggestQuoteStyle("simple"),
        )
        assertEquals(
            StringEscapeUtils.QuoteStyle.DOUBLE,
            StringEscapeUtils.suggestQuoteStyle("it's"),
        )
        assertEquals(
            StringEscapeUtils.QuoteStyle.TRIPLE_DOUBLE,
            StringEscapeUtils.suggestQuoteStyle("line1\nline2"),
        )
        assertEquals(
            StringEscapeUtils.QuoteStyle.SINGLE,
            StringEscapeUtils.suggestQuoteStyle("\$" + "notInterpolated"),
        )
    }

    @Test
    fun `round trip escape and unescape`() {
        val original = "Hello\nWorld\t\"quoted\" and 'single' and \\backslash"
        val escaped = StringEscapeUtils.escapeGroovy(original)
        val unescaped = StringEscapeUtils.unescapeGroovy(escaped)
        assertEquals(original, unescaped)
    }
}
