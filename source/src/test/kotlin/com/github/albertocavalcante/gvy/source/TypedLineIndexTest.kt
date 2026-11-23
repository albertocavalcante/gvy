// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedLineIndexTest {
    @Test
    fun `a line after the text is null in the strict forms and clamped in the others`() {
        val index = Utf16LineIndex("x\ny")
        val outside = Utf16Position.ofZeroBased(7, 0)
        assertNull(index.toCodePointOrNull(outside))
        assertFalse(index.contains(outside))
        assertNull(index.lineLength(Line.ofZeroBased(2)))
        // The clamping form answers inside the text, and nothing afterwards shows that it clamped.
        assertEquals(CodePointPosition.ofOneBased(2, 2), index.toCodePoint(outside))

        val outsideInCodePoints = CodePointPosition.ofOneBased(3, 1)
        assertNull(index.toUtf16OrNull(outsideInCodePoints))
        assertFalse(index.contains(outsideInCodePoints))
        assertEquals(Utf16Position.ofZeroBased(1, 1), index.toUtf16(outsideInCodePoints))
    }

    @Test
    fun `a character past the line end clamps in both forms, as LSP specifies`() {
        val index = Utf16LineIndex("😀\ny")
        val pastEnd = Utf16Position.ofZeroBased(0, 9)
        assertEquals(CodePointPosition.ofOneBased(1, 2), index.toCodePointOrNull(pastEnd))
        assertEquals(CodePointPosition.ofOneBased(1, 2), index.toCodePoint(pastEnd))
        assertFalse(index.contains(pastEnd))
        assertTrue(index.contains(Utf16Position.ofZeroBased(0, 2)))
        assertTrue(index.contains(CodePointPosition.ofOneBased(1, 2)))
        assertFalse(index.contains(CodePointPosition.ofOneBased(1, 3)))
        assertEquals(Utf16Position.ofZeroBased(0, 2), index.toUtf16OrNull(CodePointPosition.ofOneBased(1, 9)))
    }

    @Test
    fun `typed line length and count`() {
        val index = Utf16LineIndex("ab\r\n😀\r")
        assertEquals(3, index.lineCount)
        assertEquals(Utf16Column.ofZeroBased(2), index.lineLength(Line.ofZeroBased(0)))
        assertEquals(Utf16Column.ofZeroBased(2), index.lineLength(Line.ofZeroBased(1)))
        assertEquals(Utf16Column.ofZeroBased(0), index.lineLength(Line.ofZeroBased(2)))
        assertNull(index.lineLength(Line.ofZeroBased(3)))
        assertEquals(1, Utf16LineIndex("").lineCount)
    }

    @Test
    fun `a bare CR ends a line of the index, though compiler lines end only at LF`() {
        // FIXME(#2413): Groovy reports `b` in "a\rb" at line 1, column 3 (one-based, LF-only lines).
        //   The compiler-correct UTF-16 position is (1,0); this index splits at the CR, so the same
        //   numbers name line 0, whose column 2 clamps to (0,1). This test pins today's answer; do
        //   not convert compiler coordinates through this index until #2413 decides the line model.
        //   See: https://github.com/albertocavalcante/gvy/issues/2413
        val index = Utf16LineIndex("a\rb")
        assertEquals(Utf16Position.ofZeroBased(0, 1), index.toUtf16(CodePointPosition.ofOneBased(1, 3)))
    }

    @Test
    fun `typed positions and spans convert back to the raw change shape`() {
        val start = Utf16Position.ofZeroBased(1, 2)
        val end = Utf16Position.ofZeroBased(3, 0)
        assertEquals(RawUtf16Position(1, 2), start.toRaw())
        assertEquals(RawUtf16Range(RawUtf16Position(1, 2), RawUtf16Position(3, 0)), Utf16Span.of(start, end).toRaw())
        assertEquals(start, Utf16Position.ofRawOrNull(start.toRaw()))
        assertEquals(Utf16Span.of(start, end), Utf16Span.ofRawOrNull(Utf16Span.of(start, end).toRaw()))
    }

    @Test
    fun `a long line without surrogates takes the constant-time path`() {
        val ascii = "x".repeat(LONG_LINE)
        val index = Utf16LineIndex("$ascii\n😀$ascii\n\uD83D")
        assertEquals(ColumnUnits.Identity, index.columnUnitsOf(0))
        assertEquals(ColumnUnits.Walking, index.columnUnitsOf(1))
        assertEquals(ColumnUnits.Walking, index.columnUnitsOf(2)) // A lone surrogate also needs the walk.

        // The identity path still clamps, and agrees with the general walk at every sampled column.
        for (column in listOf(0, 1, LONG_LINE / 2, LONG_LINE - 1, LONG_LINE, LONG_LINE + 5)) {
            val expected = minOf(column, LONG_LINE)
            val viaIndex = index.toCodePoint(Utf16Position.ofZeroBased(0, column))
            assertEquals(CodePointPosition.of(Line.ofZeroBased(0), CodePointColumn.ofZeroBased(expected)), viaIndex)
            assertEquals(expected, ColumnUnits.Walking.codePointColumn(ascii, 0, ascii.length, column))
            assertEquals(
                Utf16Position.ofZeroBased(0, expected),
                index.toUtf16(CodePointPosition.of(Line.ofZeroBased(0), CodePointColumn.ofZeroBased(column))),
            )
        }
    }

    private companion object {
        const val LONG_LINE = 200_000
    }
}
