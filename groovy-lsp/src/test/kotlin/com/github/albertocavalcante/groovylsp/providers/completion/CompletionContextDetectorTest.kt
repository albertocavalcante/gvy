package com.github.albertocavalcante.groovylsp.providers.completion

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompletionContextDetectorTest {

    @Test
    fun `isCleanInsertion returns false when inside identifier`() {
        val content = "def fooBar = 1"
        // Cursor on the 'B' in fooBar
        assertFalse(CompletionContextDetector.isCleanInsertion(content, line = 0, character = 7))
    }

    @Test
    fun `isCleanInsertion returns false at identifier boundary`() {
        val content = "def foo = 1"
        // Cursor right after "foo" (inserting would extend the identifier)
        assertFalse(CompletionContextDetector.isCleanInsertion(content, line = 0, character = 7))
    }

    @Test
    fun `isCleanInsertion returns true at operator boundary`() {
        val content = "def foo = 1"
        // Cursor on '=' (surrounded by non-identifier chars)
        assertTrue(CompletionContextDetector.isCleanInsertion(content, line = 0, character = 8))
    }

    @Test
    fun `insertDummyIdentifier inserts dummy identifier and fixes hanging assignment`() {
        val content = "def x =\nprintln('hi')"
        val updated = CompletionContextDetector.insertDummyIdentifier(content, line = 1, character = 0, withDef = false)

        val lines = updated.lines()
        assertEquals("def x = null;", lines[0])
        assertTrue(lines[1].startsWith(CompletionProvider.DUMMY_IDENTIFIER))
    }

    @Test
    fun `insertDummyIdentifier can insert def strategy`() {
        val content = "class Foo {\n}\n"
        val updated = CompletionContextDetector.insertDummyIdentifier(content, line = 1, character = 0, withDef = true)
        assertTrue(updated.lines()[1].startsWith("def ${CompletionProvider.DUMMY_IDENTIFIER}"))
    }

    @Test
    fun `detectImportCompletionContext extracts qualified prefix`() {
        val lineText = "import java.ut"
        val ctx = CompletionContextDetector.detectImportCompletionContext(
            content = lineText,
            line = 0,
            character = lineText.length,
            tokenIndex = null,
        )

        assertNotNull(ctx)
        assertEquals("java.ut", ctx.prefix)
        assertEquals(false, ctx.isStatic)
        assertEquals(true, ctx.canSuggestStatic)
        assertEquals(0, ctx.line)
        assertEquals(7, ctx.replaceStartCharacter)
        assertEquals(lineText.length, ctx.replaceEndCharacter)
    }

    @Test
    fun `detectImportCompletionContext recognizes static import and omits static keyword suggestion`() {
        val lineText = "import static java.lang.Ma"
        val ctx = CompletionContextDetector.detectImportCompletionContext(
            content = lineText,
            line = 0,
            character = lineText.length,
            tokenIndex = null,
        )

        assertNotNull(ctx)
        assertEquals("java.lang.Ma", ctx.prefix)
        assertTrue(ctx.isStatic)
        assertFalse(ctx.canSuggestStatic)
        assertEquals(14, ctx.replaceStartCharacter)
        assertEquals(lineText.length, ctx.replaceEndCharacter)
    }

    @Test
    fun `detectImportCompletionContext treats partial static as typing static`() {
        val lineText = "import st"
        val ctx = CompletionContextDetector.detectImportCompletionContext(
            content = lineText,
            line = 0,
            character = lineText.length,
            tokenIndex = null,
        )

        assertNotNull(ctx)
        assertEquals("", ctx.prefix)
        assertFalse(ctx.isStatic)
        assertTrue(ctx.canSuggestStatic)
        assertEquals(lineText.length, ctx.replaceStartCharacter)
        assertEquals(lineText.length, ctx.replaceEndCharacter)
    }
}
