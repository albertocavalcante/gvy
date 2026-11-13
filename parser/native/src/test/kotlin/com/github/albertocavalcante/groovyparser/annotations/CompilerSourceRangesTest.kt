// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.annotations

import com.github.albertocavalcante.nativeapi.ParserPosition
import com.github.albertocavalcante.nativeapi.ParserRange
import org.codehaus.groovy.ast.ASTNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilerSourceRangesTest {
    @Test
    fun `source size limit is inclusive and rejects oversized lines and newline-heavy documents`() {
        val limit = CompilerSourceRanges.MAX_SOURCE_UTF16_UNITS
        val atLimit = CompilerSourceRanges("x".repeat(limit))
        assertTrue(atLimit.available)
        assertEquals(ParserRange(ParserPosition(0, 0), ParserPosition(0, limit)), atLimit.range(node(1, limit + 1)))

        for (source in listOf("x".repeat(limit + 1), "\n".repeat(limit + 1))) {
            val ranges = CompilerSourceRanges(source)
            assertFalse(ranges.available)
            assertNull(ranges.range(node(1, 2)))
        }
    }

    @Test
    fun `line count limit includes CR LF and CRLF line breaks`() {
        val limit = CompilerSourceRanges.MAX_SOURCE_LINES
        for (lineBreak in listOf("\n", "\r", "\r\n")) {
            val atLimit = CompilerSourceRanges(lineBreak.repeat(limit - 1))
            val overLimit = CompilerSourceRanges(lineBreak.repeat(limit))
            assertTrue(atLimit.available)
            assertFalse(overLimit.available)
            assertNull(overLimit.range(node(1, 1)))
        }
    }

    @Test
    fun `supplementary code points map to UTF16 positions`() {
        val ranges = CompilerSourceRanges("a😀b\nsecond")
        val result = ranges.range(node(2, 4))
        assertEquals(ParserRange(ParserPosition(0, 1), ParserPosition(0, 4)), result)
        assertEquals("😀b", ranges.text(requireNotNull(result)))
    }

    @Test
    fun `bare CR maps to a new compiler source line and excludes line endings`() {
        val ranges = CompilerSourceRanges("first\rsecond")
        val result = ranges.range(node(line = 2, startColumn = 1, endColumn = 7))

        assertEquals(ParserRange(ParserPosition(1, 0), ParserPosition(1, 6)), result)
        assertEquals("second", ranges.text(requireNotNull(result)))
    }

    private fun node(line: Int = 1, startColumn: Int, endColumn: Int): ASTNode = ASTNode().apply {
        lineNumber = line
        columnNumber = startColumn
        lastLineNumber = line
        lastColumnNumber = endColumn
    }
}
