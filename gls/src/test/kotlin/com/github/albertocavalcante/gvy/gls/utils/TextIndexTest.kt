package com.github.albertocavalcante.gvy.gls.utils

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextIndexTest {
    private val index = TextIndex("ab\n\ncde\nf")

    @Test
    fun `positionOf finds the line and column of every offset`() {
        val expected = listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 2 to 3, 3 to 0, 3 to 1)
        expected.forEachIndexed { offset, (line, col) ->
            assertEquals(Position(line, col), index.positionOf(offset), "offset $offset")
        }
    }

    @Test
    fun `offsetOf inverts positionOf and clamps past the end`() {
        for (offset in 0..9) assertEquals(offset, index.offsetOf(index.positionOf(offset)))
        assertEquals(9, index.offsetOf(Position(9, 0)))
        assertEquals(9, index.offsetOf(Position(3, 99)))
    }
}
