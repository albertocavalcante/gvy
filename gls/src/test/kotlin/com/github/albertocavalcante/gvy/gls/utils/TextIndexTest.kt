// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `Groovy columns convert supplementary characters to UTF16 across CRLF lines`() {
        val text = TextIndex("😀a\r\n\r\n😀😀b")
        assertEquals(Position(0, 2), text.utf16Position(Position(0, 1)))
        assertEquals(Position(2, 4), text.utf16Position(Position(2, 2)))
        assertEquals(Position(0, 1), text.codePointPosition(Position(0, 2)))
        assertEquals(Position(2, 2), text.codePointPosition(Position(2, 4)))
        assertEquals(Position(1, 0), text.utf16Position(Position(1, 0)))
    }

    @Test
    fun `source coordinate conversion rejects positions outside real line bounds`() {
        val text = TextIndex("😀a\r\n")
        assertNull(text.utf16Position(Position(0, 3)))
        assertNull(text.codePointPosition(Position(0, 4)))
        assertNull(text.utf16Position(Position(-1, 0)))
        assertNull(text.codePointPosition(Position(2, 0)))
        assertNull(text.utf16Position(Position(0, -1)))
    }

    @Test
    fun `identifier lookup uses protocol coordinates across mixed line endings`() {
        val text = TextIndex("😀\rignored\r\nvalue")
        assertEquals(
            Range(Position(2, 0), Position(2, 5)),
            firstNameOccurrence(text, Range(Position(0, 0), Position(2, 5)), "value"),
        )
    }
}
