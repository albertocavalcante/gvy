package com.github.albertocavalcante.groovycommon.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextIndexTest {

    @Test
    fun `offsetAt returns 0 for position 0_0`() {
        val content = "hello\nworld"
        assertEquals(0, TextIndex.offsetAt(content, 0, 0))
    }

    @Test
    fun `offsetAt returns correct offset for first line`() {
        val content = "hello\nworld"
        assertEquals(3, TextIndex.offsetAt(content, 0, 3)) // 'l' in hello
    }

    @Test
    fun `offsetAt returns correct offset for second line`() {
        val content = "hello\nworld"
        // Line 0: "hello" (5 chars) + newline (1) = offset 6 for line 1 start
        assertEquals(6, TextIndex.offsetAt(content, 1, 0)) // 'w'
        assertEquals(9, TextIndex.offsetAt(content, 1, 3)) // 'l' in world
    }

    @Test
    fun `offsetAt clamps character to line length`() {
        val content = "hi\nbye"
        // Line 0 has 2 chars, requesting char 10 should clamp to 2
        assertEquals(2, TextIndex.offsetAt(content, 0, 10))
    }

    @Test
    fun `offsetAt returns content length for line beyond end`() {
        val content = "hello"
        assertEquals(5, TextIndex.offsetAt(content, 5, 0))
    }

    @Test
    fun `offsetAt handles empty content`() {
        assertEquals(0, TextIndex.offsetAt("", 0, 0))
        assertEquals(0, TextIndex.offsetAt("", 1, 5))
    }

    @Test
    fun `offsetAt handles negative line`() {
        assertEquals(0, TextIndex.offsetAt("hello", -1, 0))
    }

    @Test
    fun `positionAt returns 0_0 for offset 0`() {
        val content = "hello\nworld"
        assertEquals(0 to 0, TextIndex.positionAt(content, 0))
    }

    @Test
    fun `positionAt returns correct position for offset in first line`() {
        val content = "hello\nworld"
        assertEquals(0 to 3, TextIndex.positionAt(content, 3))
    }

    @Test
    fun `positionAt returns correct position for offset in second line`() {
        val content = "hello\nworld"
        assertEquals(1 to 0, TextIndex.positionAt(content, 6)) // 'w'
        assertEquals(1 to 3, TextIndex.positionAt(content, 9)) // 'l'
    }

    @Test
    fun `positionAt clamps to content length`() {
        val content = "hello"
        assertEquals(0 to 5, TextIndex.positionAt(content, 100))
    }

    @Test
    fun `countOccurrences finds all non-overlapping matches`() {
        assertEquals(3, TextIndex.countOccurrences("abcabcabc", "abc"))
        assertEquals(3, TextIndex.countOccurrences("aaa", "a"))
        assertEquals(0, TextIndex.countOccurrences("hello", "xyz"))
    }

    @Test
    fun `countOccurrences handles empty needle`() {
        assertEquals(0, TextIndex.countOccurrences("hello", ""))
    }

    @Test
    fun `countOccurrences handles needle longer than haystack`() {
        assertEquals(0, TextIndex.countOccurrences("hi", "hello"))
    }

    @Test
    fun `lineAt returns correct line`() {
        val content = "first\nsecond\nthird"
        assertEquals("first", TextIndex.lineAt(content, 0))
        assertEquals("second", TextIndex.lineAt(content, 1))
        assertEquals("third", TextIndex.lineAt(content, 2))
    }

    @Test
    fun `lineAt returns empty for out of bounds`() {
        assertEquals("", TextIndex.lineAt("hello", 5))
        assertEquals("", TextIndex.lineAt("hello", -1))
    }

    @Test
    fun `isAtLineStart returns true for cursor at beginning`() {
        val content = "    hello"
        assertTrue(TextIndex.isAtLineStart(content, 0, 0))
        assertTrue(TextIndex.isAtLineStart(content, 0, 2))
        assertTrue(TextIndex.isAtLineStart(content, 0, 4))
    }

    @Test
    fun `isAtLineStart returns false for cursor after non-whitespace`() {
        val content = "    hello"
        assertFalse(TextIndex.isAtLineStart(content, 0, 5)) // After 'h'
        assertFalse(TextIndex.isAtLineStart(content, 0, 9))
    }

    @Test
    fun `isAtLineStart handles tabs`() {
        val content = "\t\thello"
        assertTrue(TextIndex.isAtLineStart(content, 0, 2))
        assertFalse(TextIndex.isAtLineStart(content, 0, 3))
    }

    @Test
    fun `isAtLineStart returns true for empty line`() {
        val content = "first\n\nthird"
        assertTrue(TextIndex.isAtLineStart(content, 1, 0))
    }
}
