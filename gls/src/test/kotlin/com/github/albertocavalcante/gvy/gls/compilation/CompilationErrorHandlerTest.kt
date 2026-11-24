// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class CompilationErrorHandlerTest {

    private val handler = CompilationErrorHandler()
    private val uri = URI.create("file:///workspace/script.groovy")

    private class SyntaxFailure(override val message: String) : CompilationFailedException(0, null)

    private fun startOf(message: String): Position {
        val result = handler.handleException(SyntaxFailure(message), uri)
        return result.diagnostics.single().range.start
    }

    @Test
    fun `syntax error with at-line-column position maps to zero-based range`() {
        assertEquals(Position(4, 9), startOf("Syntax error @ line 5, column 10."))
    }

    @Test
    fun `syntax error with colon separated line and column is case insensitive`() {
        assertEquals(Position(6, 2), startOf("Syntax error: LINE: 7, Column: 3"))
    }

    @Test
    fun `syntax error with line only maps column to the start of the line`() {
        assertEquals(Position(2, 0), startOf("Syntax error at line 3"))
    }

    @Test
    fun `syntax error without position falls back to the document start`() {
        assertEquals(Position(0, 0), startOf("Syntax error somewhere"))
    }
}
