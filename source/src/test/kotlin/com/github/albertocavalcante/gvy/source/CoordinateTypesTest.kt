// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.math.sign
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinateTypesTest {
    @Test
    fun `lines name their base only at the Int boundary`() {
        assertEquals(Line.ofZeroBased(0), Line.ofOneBased(1))
        assertEquals(4, Line.ofZeroBased(3).oneBased)
        assertEquals(2, Line.ofOneBased(3).zeroBased)
        assertEquals(Int.MAX_VALUE, Line.ofZeroBased(Line.MAX_ZERO_BASED).oneBased)
        assertEquals(Line.MAX_ZERO_BASED, Line.ofOneBased(Int.MAX_VALUE).zeroBased)
        assertTrue(Line.ofZeroBased(1) > Line.ofZeroBased(0))
        assertEquals("Line(zeroBased=0, oneBased=1)", Line.ofZeroBased(0).toString())

        assertFailsWith<IllegalArgumentException> { Line.ofZeroBased(-1) }
        assertFailsWith<IllegalArgumentException> { Line.ofZeroBased(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { Line.ofOneBased(0) }
        assertFailsWith<IllegalArgumentException> { Line.ofOneBased(Int.MIN_VALUE) }
        assertNull(Line.ofZeroBasedOrNull(-1))
        assertNull(Line.ofZeroBasedOrNull(Int.MAX_VALUE))
        assertNull(Line.ofOneBasedOrNull(-1)) // A compiler's synthetic node.
        assertNull(Line.ofOneBasedOrNull(0))
        assertEquals(Line.ofZeroBased(6), Line.ofOneBasedOrNull(7))
        assertEquals(Line.ofZeroBased(7), Line.ofZeroBasedOrNull(7))
    }

    @Test
    fun `columns name their base and keep their unit`() {
        assertEquals(Utf16Column.ofZeroBased(0), Utf16Column.ofOneBased(1))
        assertEquals(CodePointColumn.ofZeroBased(0), CodePointColumn.ofOneBased(1))
        assertEquals(Int.MAX_VALUE, Utf16Column.ofZeroBased(Utf16Column.MAX_ZERO_BASED).oneBased)
        assertEquals(Int.MAX_VALUE, CodePointColumn.ofZeroBased(CodePointColumn.MAX_ZERO_BASED).oneBased)
        assertTrue(CodePointColumn.ofZeroBased(2) > CodePointColumn.ofZeroBased(1))
        assertTrue(Utf16Column.ofZeroBased(2) > Utf16Column.ofZeroBased(1))
        assertEquals("Utf16Column(zeroBased=2, oneBased=3)", Utf16Column.ofZeroBased(2).toString())
        assertEquals("CodePointColumn(zeroBased=2, oneBased=3)", CodePointColumn.ofZeroBased(2).toString())

        for (invalid in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Utf16Column.ofZeroBased(invalid) }
            assertFailsWith<IllegalArgumentException> { CodePointColumn.ofZeroBased(invalid) }
            assertNull(Utf16Column.ofZeroBasedOrNull(invalid))
            assertNull(CodePointColumn.ofZeroBasedOrNull(invalid))
        }
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Utf16Column.ofOneBased(invalid) }
            assertFailsWith<IllegalArgumentException> { CodePointColumn.ofOneBased(invalid) }
            assertNull(Utf16Column.ofOneBasedOrNull(invalid))
            assertNull(CodePointColumn.ofOneBasedOrNull(invalid))
        }
        assertEquals(Utf16Column.ofZeroBased(4), Utf16Column.ofOneBasedOrNull(5))
        assertEquals(CodePointColumn.ofZeroBased(4), CodePointColumn.ofOneBasedOrNull(5))
        assertEquals(Utf16Column.ofZeroBased(5), Utf16Column.ofZeroBasedOrNull(5))
        assertEquals(CodePointColumn.ofZeroBased(5), CodePointColumn.ofZeroBasedOrNull(5))
    }

    @Test
    fun `positions keep both parts, including the largest values`() {
        val corners = listOf(0, 1, 2, Line.MAX_ZERO_BASED - 1, Line.MAX_ZERO_BASED)
        for (line in corners) {
            for (column in corners) {
                val utf16 = Utf16Position.ofZeroBased(line, column)
                assertEquals(line, utf16.line.zeroBased)
                assertEquals(column, utf16.column.zeroBased)
                val codePoint = CodePointPosition.ofOneBased(line + 1, column + 1)
                assertEquals(line, codePoint.line.zeroBased)
                assertEquals(column, codePoint.column.zeroBased)
            }
        }
        assertFailsWith<IllegalArgumentException> { Utf16Position.ofZeroBased(-1, 0) }
        assertFailsWith<IllegalArgumentException> { Utf16Position.ofZeroBased(0, -1) }
        assertFailsWith<IllegalArgumentException> { CodePointPosition.ofOneBased(0, 1) }
        assertFailsWith<IllegalArgumentException> { CodePointPosition.ofOneBased(1, 0) }
        assertEquals("Utf16Position(line=1, column=2 [0-based])", Utf16Position.ofZeroBased(1, 2).toString())
        assertEquals("CodePointPosition(line=1, column=2 [0-based])", CodePointPosition.ofOneBased(2, 3).toString())
    }

    @Test
    fun `positions order by line then column`() {
        val corners = listOf(0, 1, 2, Line.MAX_ZERO_BASED)
        val pairs = corners.flatMap { line -> corners.map { column -> line to column } }
        for ((lineA, columnA) in pairs) {
            for ((lineB, columnB) in pairs) {
                val expected = compareValuesBy(lineA to columnA, lineB to columnB, { it.first }, { it.second })
                val utf16A = Utf16Position.ofZeroBased(lineA, columnA)
                val utf16B = Utf16Position.ofZeroBased(lineB, columnB)
                val codePointA = CodePointPosition.ofOneBased(lineA + 1, columnA + 1)
                val codePointB = CodePointPosition.ofOneBased(lineB + 1, columnB + 1)
                val utf16 = utf16A.compareTo(utf16B)
                val codePoint = codePointA.compareTo(codePointB)
                assertEquals(expected.sign, utf16.sign, "($lineA,$columnA) vs ($lineB,$columnB)")
                assertEquals(expected.sign, codePoint.sign, "($lineA,$columnA) vs ($lineB,$columnB)")
            }
        }
    }

    @Test
    fun `raw positions and ranges validate into typed ones`() {
        assertEquals(Utf16Position.ofZeroBased(1, 2), Utf16Position.ofRawOrNull(RawUtf16Position(1, 2)))
        assertNull(Utf16Position.ofRawOrNull(RawUtf16Position(-1, 0)))
        assertNull(Utf16Position.ofRawOrNull(RawUtf16Position(0, -1)))
        assertNull(Utf16Position.ofRawOrNull(RawUtf16Position(Int.MAX_VALUE, 0)))
        assertNull(Utf16Position.ofRawOrNull(RawUtf16Position(0, Int.MAX_VALUE)))

        val raw = RawUtf16Range(RawUtf16Position(0, 3), RawUtf16Position(1, 0))
        assertEquals(
            Utf16Span.of(Utf16Position.ofZeroBased(0, 3), Utf16Position.ofZeroBased(1, 0)),
            Utf16Span.ofRawOrNull(raw),
        )
        assertNull(Utf16Span.ofRawOrNull(RawUtf16Range(RawUtf16Position(1, 0), RawUtf16Position(0, 3))))
        assertNull(Utf16Span.ofRawOrNull(RawUtf16Range(RawUtf16Position(0, -3), RawUtf16Position(1, 0))))
        assertNull(Utf16Span.ofRawOrNull(RawUtf16Range(RawUtf16Position(0, 3), RawUtf16Position(-1, 0))))
    }

    @Test
    fun `spans reject an end before the start`() {
        val early = Utf16Position.ofZeroBased(0, 5)
        val late = Utf16Position.ofZeroBased(1, 0)
        val failure = assertFailsWith<IllegalArgumentException> { Utf16Span.of(late, early) }
        assertEquals(
            "A span must not end before it starts: " +
                "Utf16Position(line=1, column=0 [0-based]) > Utf16Position(line=0, column=5 [0-based])",
            failure.message,
        )
        assertNull(Utf16Span.ofOrNull(late, early))
        assertFailsWith<IllegalArgumentException> {
            CodePointSpan.of(CodePointPosition.ofOneBased(1, 3), CodePointPosition.ofOneBased(1, 2))
        }
        assertNull(CodePointSpan.ofOrNull(CodePointPosition.ofOneBased(2, 1), CodePointPosition.ofOneBased(1, 9)))

        val span = Utf16Span.of(early, late)
        assertEquals(early, span.start)
        assertEquals(late, span.endExclusive)
        assertFalse(span.isEmpty)
        assertTrue(Utf16Span.of(early, early).isEmpty)
        assertEquals(span, Utf16Span.ofOrNull(early, late))
        assertEquals(span.hashCode(), Utf16Span.of(early, late).hashCode())
        assertNotEquals(span, Utf16Span.of(early, early))
        assertNotEquals<Any>(span, early)

        val codePoints = CodePointSpan.of(CodePointPosition.ofOneBased(1, 1), CodePointPosition.ofOneBased(1, 4))
        assertEquals(codePoints, CodePointSpan.ofOrNull(codePoints.start, codePoints.endExclusive))
        assertEquals(codePoints.hashCode(), CodePointSpan.of(codePoints.start, codePoints.endExclusive).hashCode())
        assertFalse(codePoints.isEmpty)
        assertTrue(CodePointSpan.of(codePoints.start, codePoints.start).isEmpty)
        assertEquals(
            "CodePointSpan(CodePointPosition(line=0, column=0 [0-based]) " +
                "until CodePointPosition(line=0, column=3 [0-based]))",
            codePoints.toString(),
        )
    }
}
