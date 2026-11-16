// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.converters

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SourcePositionsTest {
    // Line 1 is `'<emoji>' x`: the emoji is two UTF-16 units but one Groovy column.
    private val source = "a\n'😀' x"

    @Test
    fun `counts a supplementary character as one Groovy column`() {
        val groovy = Position(1, 5).toGroovyPosition(source)

        assertEquals(1, groovy.line)
        assertEquals(4, groovy.character)
    }

    @Test
    fun `copies the columns without source text or for a position outside it`() {
        assertEquals(5, Position(1, 5).toGroovyPosition(null).character)
        assertEquals(40, Position(9, 40).toGroovyPosition(source).character)
    }
}
