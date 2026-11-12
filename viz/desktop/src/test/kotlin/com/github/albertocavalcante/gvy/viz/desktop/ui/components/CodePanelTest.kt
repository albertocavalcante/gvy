package com.github.albertocavalcante.gvy.viz.desktop.ui.components

import com.github.albertocavalcante.gvy.viz.desktop.state.CodeError
import com.github.albertocavalcante.gvy.viz.desktop.state.SourceCoordinates
import com.github.albertocavalcante.gvy.viz.desktop.state.errorSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodePanelTest {
    private fun offset(text: String, line: Int, column: Int): Int = SourceCoordinates(text).offset(line, column) ?: -1

    @Test
    fun `offsets stay on their line across LF and CRLF`() {
        for (text in listOf("a\nbc", "a\r\nbc")) {
            assertEquals(-1, offset(text, 1, 4))
            assertEquals(-1, offset(text, 1, 0))
            assertEquals(text.indexOf('b'), offset(text, 2, 1))
            assertEquals(text.length, offset(text, 2, 3))
        }
    }
    private fun span(text: String, error: CodeError): IntRange? = errorSpans(text, listOf(error)).singleOrNull()

    @Test
    fun `diagnostic spans are exclusive for first and later lines and points remain empty`() {
        for (separator in listOf("\n", "\r\n")) {
            val source = "abc" + separator + "def"
            assertEquals(0 until 2, span(source, CodeError("first", 1, 1, 1, 3)))
            assertEquals(4 + separator.length until 6 + separator.length, span(source, CodeError("later", 2, 2, 2, 4)))
            assertNull(span(source, CodeError("point", 1, 2, 1, 2)))
            assertNull(span(source, CodeError("stale", 1, 5, 1, 6)))
        }
    }

    @Test
    fun `columns count UTF-16 code units`() {
        assertEquals(3, offset("😀x\ny", 1, 4))
        assertEquals(-1, offset("😀x\ny", 1, 5))
    }
}
