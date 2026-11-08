package com.github.albertocavalcante.gvy.gls.utils

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextIndexLineEndingsTest {
    @Test
    fun `protocol lines and Groovy lexer lines round trip across all line endings`() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val source = "ab" + ending + "𐐀x" + ending
            val index = TextIndex(source)
            val groovy = if (ending == "\r") Position(0, 4) else Position(1, 1)
            assertEquals(Position(1, 2), index.utf16Position(groovy), ending)
            assertEquals(groovy, index.codePointPosition(Position(1, 2)), ending)
            assertEquals(Range(Position(1, 0), Position(1, 3)), index.lineRange(1), ending)
            assertEquals(Position(2, 0), index.positionOf(source.length), ending)
            assertNull(index.codePointPosition(Position(1, 4)), ending)
            for (position in listOf(Position(0, 0), Position(0, 2), Position(1, 0), Position(1, 3), Position(2, 0))) {
                assertEquals(position, index.positionOf(index.offsetOf(position)), ending)
            }
        }
    }
}
