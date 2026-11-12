// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.folding

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoldingRangeBuilderTest {
    @Test
    fun `response limits keep earliest valid folds deterministic`() {
        val count = FoldingRangeBuilder.MAX_FOLDING_RANGES + 3
        val source = "x\n".repeat(count * 2 + 1)
        val nodes = (0 until count).map { line ->
            BlockStatement().apply {
                lineNumber = line * 2 + 1
                columnNumber = 1
                lastLineNumber = line * 2 + 3
                lastColumnNumber = 1
            }
        }
        val ranges = FoldingRangeBuilder(TextIndex(source), nodes.reversed()).build {}
        assertEquals(FoldingRangeBuilder.MAX_FOLDING_RANGES, ranges.size)
        assertEquals(0, ranges.first().startLine)
        assertTrue(ranges.zipWithNext().all { (left, right) -> left.startLine < right.startLine })
        assertTrue(ranges.all { it.endLine > it.startLine && it.endLine < count * 2 + 1 })
    }

    @Test
    fun `cancellation during tokenization never becomes an empty success`() {
        var checks = 0
        val source = "/* docs\n comment */\n".repeat(100)
        assertThrows<CancellationException> {
            FoldingRangeBuilder(TextIndex(source), emptyList()).build {
                if (++checks == 3) throw CancellationException("cancelled")
            }
        }
    }
}
