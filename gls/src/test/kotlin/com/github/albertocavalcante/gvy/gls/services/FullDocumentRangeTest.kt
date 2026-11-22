// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FullDocumentRangeTest {
    private fun assertEnd(text: String, line: Int, column: Int) {
        assertEquals(Range(Position(0, 0), Position(line, column)), text.toFullDocumentRange(), "text=${text.length}")
    }

    @Test
    fun `ends at the last line the way LSP counts lines`() {
        assertEnd("", 0, 0)
        assertEnd("abc", 0, 3)
        assertEnd("a\nbc", 1, 2)
        assertEnd("a\n", 1, 0)
        assertEnd("a\r\nbc", 1, 2)
        assertEnd("a\r\n", 1, 0)
    }

    @Test
    fun `counts a bare CR as a line break`() {
        assertEnd("a\rbc", 1, 2)
        assertEnd("a\r", 1, 0)
        assertEnd("a\nb\rc", 2, 1)
        assertEnd("a\rb\rcd", 2, 2)
    }
}
