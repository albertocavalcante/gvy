// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentifierRangesUnicodeBoundaryTest {
    @Test
    fun `an astral identifier prefix cannot be mistaken for a word boundary`() {
        val index = TextIndex("𐐀value")
        assertNull(firstNameOccurrence(index, Range(Position(0, 0), Position(0, 7)), "value"))
    }

    @Test
    fun `an astral identifier suffix cannot be mistaken for a word boundary`() {
        val index = TextIndex("value𐐀")
        assertNull(firstNameOccurrence(index, Range(Position(0, 0), Position(0, 7)), "value"))
    }

    @Test
    fun `whitespace beside astral letters remains a real boundary`() {
        val index = TextIndex("𐐀 value 𐐀")
        assertEquals(
            Range(Position(0, 3), Position(0, 8)),
            firstNameOccurrence(index, Range(Position(0, 0), Position(0, 11)), "value"),
        )
    }
}
