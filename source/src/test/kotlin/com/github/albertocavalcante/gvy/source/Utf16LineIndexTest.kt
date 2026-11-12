// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Utf16LineIndexTest {
    @Test
    fun `mixed line endings and surrogate pairs preserve UTF16 offsets`() {
        val index = Utf16LineIndex("a😀\rb\r\nc\n")
        val positions = listOf(
            0 to 0, 0 to 1, 0 to 2, 0 to 3, 1 to 0,
            1 to 1, 1 to 2, 2 to 0, 2 to 1, 3 to 0,
        )
        positions.forEachIndexed { offset, (line, column) ->
            assertEquals(line, index.lineAt(offset), "offset $offset")
            assertEquals(offset, index.offsetOf(line, column), "offset $offset")
        }
        assertEquals(listOf(3, 1, 1, 0), (0..3).map(index::lineLength))
        assertTrue(index.isValidPosition(0, 2))
        assertFalse(index.isValidPosition(1, 2)) // Interior of CRLF.
        assertFalse(index.isValidPosition(-1, 0))
        assertFalse(index.isValidPosition(0, -1))
        assertNull(index.lineLength(4))
        assertEquals(9, index.offsetOf(99, 0))
        assertEquals(9, index.offsetOf(3, 99))
        // Preserve the facade's historical document-only clamp, even for invalid columns.
        assertEquals(4, index.offsetOf(0, 4))
        assertEquals(Int.MIN_VALUE + 3, index.offsetOf(1, Int.MAX_VALUE))
        assertFailsWith<IndexOutOfBoundsException> { index.offsetOf(-1, 0) }
        assertEquals(0, index.lineAt(-1))
    }

    @Test
    fun `empty and trailing lines retain an addressable end position`() {
        for (text in listOf("", "\n", "\r", "\r\n")) {
            val index = Utf16LineIndex(text)
            val endLine = if (text.isEmpty()) 0 else 1
            assertEquals(endLine, index.lineAt(text.length))
            assertEquals(0, index.lineLength(endLine))
            assertTrue(index.isValidPosition(endLine, 0))
        }
    }
}
