package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SourceLocationTest {
    @Test
    fun `positions reject negative coordinates and ranges reject reversed endpoints`() {
        assertFailsWith<IllegalArgumentException> { Utf16Position(-1, 0) }
        assertFailsWith<IllegalArgumentException> { Utf16Position(0, -1) }
        assertFailsWith<IllegalArgumentException> { Utf16Range(Utf16Position(1, 1), Utf16Position(1, 0)) }
        assertFailsWith<IllegalArgumentException> { Utf16Range(Utf16Position(1, 0), Utf16Position(0, 9)) }
        assertFailsWith<IllegalArgumentException> {
            Utf16Range(Utf16Position(Int.MAX_VALUE, 0), Utf16Position(0, Int.MAX_VALUE))
        }
        Utf16Range(Utf16Position(0, Int.MAX_VALUE), Utf16Position(Int.MAX_VALUE, 0))
    }

    @Test
    fun `ranges preserve zero width multiline and UTF16 positions without inspecting source`() {
        val point = Utf16Position(1, 2)
        assertEquals(point, Utf16Range(point, point).endExclusive)
        val index = Utf16LineIndex("first\r\n😀x\n")
        val afterEmoji = Utf16Position(1, 2)
        assertEquals(9, index.offsetOf(afterEmoji.line, afterEmoji.character))
        val range = Utf16Range(afterEmoji, Utf16Position(2, 0))
        assertEquals(afterEmoji, range.start)
        assertEquals(Utf16Position(2, 0), range.endExclusive)
    }

    @Test
    fun `location retains supplied URI spelling and supports unknown range`() {
        val uri = "file:///work/A%2fb%20%23.groovy"
        val location = SourceLocation(uri, null)
        assertEquals(uri, location.uri)
        assertNull(location.range)
    }
}
