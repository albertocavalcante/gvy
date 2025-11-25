package com.github.albertocavalcante.groovylsp.providers.codeaction

import com.github.albertocavalcante.groovylsp.services.Formatter
import org.eclipse.lsp4j.CodeActionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormattingActionTest {

    private val testUri = "file:///test.groovy"

    @Test
    fun `creates formatting action when content changes`() {
        val formatter = TestFormatter { "def x = 1" }
        val action = FormattingAction(formatter)

        val codeAction = action.createFormattingAction(testUri, "def x=1")

        assertNotNull(codeAction)
        assertEquals("Format document", codeAction?.title)
        assertEquals(CodeActionKind.SourceFixAll, codeAction?.kind)
        assertNotNull(codeAction?.edit)

        val changes = codeAction?.edit?.changes
        assertNotNull(changes)
        assertEquals(1, changes?.size)
        assertTrue(changes?.containsKey(testUri) == true)

        val edits = changes?.get(testUri)
        assertNotNull(edits)
        assertEquals(1, edits?.size)
        assertEquals("def x = 1", edits?.first()?.newText)
    }

    @Test
    fun `returns null when content already formatted`() {
        val formatter = TestFormatter { it } // Identity formatter
        val action = FormattingAction(formatter)

        val codeAction = action.createFormattingAction(testUri, "def x = 1")

        assertNull(codeAction)
    }

    @Test
    fun `returns null when formatter throws exception`() {
        val formatter = TestFormatter { throw IllegalStateException("Formatter error") }
        val action = FormattingAction(formatter)

        val codeAction = action.createFormattingAction(testUri, "def x=1")

        assertNull(codeAction)
    }

    @Test
    fun `handles empty content`() {
        val formatter = TestFormatter { "" }
        val action = FormattingAction(formatter)

        val codeAction = action.createFormattingAction(testUri, "some content")

        assertNotNull(codeAction)
    }

    @Test
    fun `handles multiline content`() {
        val formatter = TestFormatter { "def x = 1\ndef y = 2\n" }
        val action = FormattingAction(formatter)

        val codeAction = action.createFormattingAction(testUri, "def x=1\ndef y=2")

        assertNotNull(codeAction)
        val edits = codeAction?.edit?.changes?.get(testUri)
        assertEquals("def x = 1\ndef y = 2\n", edits?.first()?.newText)
    }

    private class TestFormatter(private val formatFn: (String) -> String) : Formatter {
        override fun format(text: String): String = formatFn(text)
    }
}
