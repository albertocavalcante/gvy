package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionRangeBuilderTest {
    @Test
    fun `supplementary identifiers and CRLF use exact UTF16 ranges`() {
        val builder = SelectionRangeBuilder(TextIndex("/* 😀 */ def 𐐀value = 1\r\n"), emptyList())
        val selection = builder.build(listOf(Position(0, 16))) {}.single()
        assertEquals(range(0, 13, 0, 20), selection.range)
        assertEquals(range(0, 0, 0, 24), selection.parent.range)
        assertEquals(range(0, 0, 1, 0), selection.parent.parent.range)
        assertNull(selection.parent.parent.parent)
    }

    @Test
    fun `whitespace and EOF retain batch cardinality with empty cursor ranges`() {
        val builder = SelectionRangeBuilder(TextIndex("\t \r\n"), emptyList())
        val result = builder.build(listOf(Position(0, 1), Position(1, 0))) {}
        assertEquals(2, result.size)
        assertEquals(range(0, 1, 0, 1), result[0].range)
        assertEquals(range(1, 0, 1, 0), result[1].range)
        assertEquals(range(0, 0, 1, 0), result[1].parent.range)
    }

    @Test
    fun `deep structural nesting stays bounded and retains the outer document`() {
        val structures = (1..100).map { range(0, 100 - it, 0, 100 + it) }
        val result = SelectionRangeBuilder(TextIndex(" ".repeat(300)), structures)
            .build(listOf(Position(0, 100))) {}.single()
        val ranges = chain(result)
        assertEquals(SelectionRangeBuilder.MAX_SELECTION_DEPTH, ranges.size)
        assertEquals(range(0, 0, 0, 300), ranges.last())
    }

    @Test
    fun `overlapping compiler spans cannot create crossing parent ranges`() {
        val structures = listOf(range(0, 1, 0, 3), range(0, 2, 0, 4), range(0, 0, 0, 4))
        val ranges = chain(
            SelectionRangeBuilder(TextIndex(" x+y "), structures)
                .build(listOf(Position(0, 2))) {}.single(),
        )
        assertFalse(range(0, 2, 0, 4) in ranges)
        for ((child, parent) in ranges.zipWithNext()) {
            assertTrue(parent.start.character <= child.start.character)
            assertTrue(parent.end.character >= child.end.character)
        }
    }

    @Test
    fun `cancellation interrupts range construction`() {
        var checks = 0
        assertThrows<CancellationException> {
            SelectionRangeBuilder(TextIndex("word"), emptyList()).build(listOf(Position(0, 1))) {
                checks++
                if (checks == 2) throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `invalid compiler spans are discarded`() {
        val structures = listOf(range(-1, 0, 0, 1), range(0, 0, 2, 0), range(0, 3, 0, 1))
        val selection = SelectionRangeBuilder(TextIndex("word"), structures)
            .build(listOf(Position(0, 1))) {}.single()
        assertEquals(range(0, 0, 0, 4), selection.range)
        assertNull(selection.parent)
    }

    @Test
    fun `empty source has one terminal empty selection`() {
        val builder = SelectionRangeBuilder(TextIndex(""), emptyList())
        val result = builder.build(listOf(Position(0, 0))) {}.single()
        assertEquals(range(0, 0, 0, 0), result.range)
        assertNull(result.parent)
        assertTrue(builder.build(emptyList()) {}.isEmpty())
    }

    private fun range(startLine: Int, start: Int, endLine: Int, end: Int) =
        Range(Position(startLine, start), Position(endLine, end))

    private fun chain(first: SelectionRange): List<Range> = buildList {
        var current: SelectionRange? = first
        while (current != null) {
            assertTrue(size <= SelectionRangeBuilder.MAX_SELECTION_DEPTH)
            add(current.range)
            current = current.parent
        }
    }
}
