package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.RangeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceCoordinatesTest {
    @Test
    fun `positions handle CR LF CRLF trailing lines and UTF-16 code units`() {
        for (separator in listOf("\r", "\n", "\r\n")) {
            val source = "a$separator😀z$separator"
            val coordinates = SourceCoordinates(source)
            val second = 1 + separator.length
            assertEquals(1 to 1, coordinates.position(-1))
            assertEquals(1 to 2, coordinates.position(1))
            assertEquals(2 to 1, coordinates.position(second))
            assertEquals(2 to 3, coordinates.position(second + 2))
            assertEquals(2 to 4, coordinates.position(second + 3))
            assertEquals(3 to 1, coordinates.position(source.length))
            assertEquals(3 to 1, coordinates.position(Int.MAX_VALUE))
            if (separator == "\r\n") assertEquals(2 to 1, coordinates.position(2))
            assertEquals(second + 2, coordinates.offset(2, 3))
            assertNull(coordinates.offset(2, 5))
            assertNull(coordinates.offset(1, Int.MAX_VALUE))
        }
        assertEquals(1 to 1, SourceCoordinates("").position(1))
    }

    @Test
    fun `invalid reversed and empty ranges stay unavailable`() {
        val coordinates = SourceCoordinates("abc\r\ndef")
        assertEquals(2 until 6, coordinates.span(RangeDto(1, 3, 2, 2)))
        for (range in listOf(RangeDto(0, 1, 1, 2), RangeDto(2, 1, 1, 1), RangeDto(1, 2, 1, 2))) {
            assertNull(coordinates.span(range))
        }
    }

    @Test
    fun `diagnostic fallback ends at the current line and invalid diagnostics are omitted`() {
        val source = "abc\r\ndef\rghi\njkl"
        val errors = listOf(
            CodeError("first", 1, 2),
            CodeError("second", 2, 2),
            CodeError("third", 3, 2),
            CodeError("missing"),
            CodeError("out of bounds", 1, 5),
            CodeError("point", 1, 2, 1, 2),
        )
        assertEquals(listOf(1 until 3, 6 until 8, 10 until 12), errorSpans(source, errors))
    }
}
