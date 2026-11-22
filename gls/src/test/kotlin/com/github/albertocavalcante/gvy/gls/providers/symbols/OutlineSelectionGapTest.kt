// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Pins a known wrong answer of the name search that the outline's selection range is built on. */
class OutlineSelectionGapTest {
    @Test
    fun `name search on a class named like its annotation finds the annotation today`() {
        val source = "@Entity class Entity {}"
        val whole = Range(Position(0, 0), Position(0, source.length))

        val found = firstNameOccurrence(TextIndex(source), whole, "Entity")

        // See: https://github.com/albertocavalcante/gvy/issues/2262
        // FIXME(#2262): the declared name is at columns 14-20; update this expectation when the search skips
        // annotation names.
        assertEquals(Range(Position(0, 1), Position(0, 7)), found)
    }
}
