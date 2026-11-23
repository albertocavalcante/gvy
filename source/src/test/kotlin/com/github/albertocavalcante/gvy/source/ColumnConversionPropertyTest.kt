// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Laws of the column converter, checked against an independent oracle (JDK code points and a
 * separate line splitter) on every text of up to [EXHAUSTIVE_LENGTH] tokens from [TOKENS], then on
 * seeded random longer texts. Plain JUnit loops rather than jqwik: this module has no jqwik engine,
 * and jqwik discovery under Bazel is unconfirmed (#2219 P0, #2063); these run identically in both.
 */
class ColumnConversionPropertyTest {
    @Test
    fun `every short text over the tricky alphabet obeys the conversion laws`() {
        var checked = 0
        forEachText(maxTokens = EXHAUSTIVE_LENGTH) { text ->
            checkLaws(text)
            checked++
        }
        assertEquals((0..EXHAUSTIVE_LENGTH).sumOf { pow(TOKENS.size, it) }, checked)
    }

    @Test
    fun `seeded random long texts obey the conversion laws`() {
        val random = Random(RANDOM_SEED)
        repeat(RANDOM_TEXTS) {
            val text = buildString {
                repeat(random.nextInt(RANDOM_MAX_TOKENS)) {
                    append(if (random.nextInt(4) == 0) TOKENS.random(random) else ('a' + random.nextInt(26)).toString())
                }
            }
            checkLaws(text)
        }
    }

    @Test
    fun `a supplementary character is two UTF-16 columns and one code-point column`() {
        val line = "😀x"
        // The x starts at UTF-16 column 2 but code-point column 1; a bare copy would answer 2.
        assertEquals(CodePointColumn.ofZeroBased(1), Utf16Column.ofZeroBased(2).toCodePoint(line))
        assertEquals(Utf16Column.ofZeroBased(2), CodePointColumn.ofZeroBased(1).toUtf16(line))
        // Between the two halves of the pair: the column of the pair's code point.
        assertEquals(CodePointColumn.ofZeroBased(0), Utf16Column.ofZeroBased(1).toCodePoint(line))
        // Past the end of the line: its end.
        assertEquals(CodePointColumn.ofZeroBased(2), Utf16Column.ofZeroBased(9).toCodePoint(line))
        assertEquals(Utf16Column.ofZeroBased(3), CodePointColumn.ofZeroBased(9).toUtf16(line))
    }

    @Test
    fun `tabs and combining marks are one code point each`() {
        // Tab, e, U+0301 COMBINING ACUTE ACCENT, a supplementary character, then x.
        val line = "\te\u0301\uD83D\uDE00x"
        assertEquals(CodePointColumn.ofZeroBased(3), Utf16Column.ofZeroBased(3).toCodePoint(line))
        assertEquals(CodePointColumn.ofZeroBased(4), Utf16Column.ofZeroBased(5).toCodePoint(line))
        assertEquals(Utf16Column.ofZeroBased(5), CodePointColumn.ofZeroBased(4).toUtf16(line))
    }

    @Test
    fun `the index converts on its own lines and clamps past the text`() {
        val index = Utf16LineIndex("a😀\rb\r\n😀\n")
        assertEquals(Line.ofZeroBased(3), index.lastLine)
        assertEquals(
            CodePointPosition.of(Line.ofZeroBased(0), CodePointColumn.ofZeroBased(2)),
            index.toCodePoint(Utf16Position.ofZeroBased(0, 3)),
        )
        // Inside the CRLF that ends line 1: the end of line 1.
        assertEquals(
            CodePointPosition.of(Line.ofZeroBased(1), CodePointColumn.ofZeroBased(1)),
            index.toCodePoint(Utf16Position.ofZeroBased(1, 2)),
        )
        assertEquals(
            Utf16Position.ofZeroBased(2, 2),
            index.toUtf16(CodePointPosition.of(Line.ofZeroBased(2), CodePointColumn.ofZeroBased(1))),
        )
        // After the last line: the end of the text, on the empty last line.
        assertEquals(
            CodePointPosition.of(Line.ofZeroBased(3), CodePointColumn.ofZeroBased(0)),
            index.toCodePoint(Utf16Position.ofZeroBased(Line.MAX_ZERO_BASED, Utf16Column.MAX_ZERO_BASED)),
        )
        assertEquals(
            Utf16Position.ofZeroBased(0, 0),
            Utf16LineIndex("").toUtf16(CodePointPosition.ofOneBased(5, 5)),
        )
        assertEquals(
            Utf16Position.ofZeroBased(0, 3),
            Utf16LineIndex("x😀").toUtf16(CodePointPosition.ofOneBased(2, 1)),
        )
        assertEquals(
            CodePointPosition.of(Line.ofZeroBased(0), CodePointColumn.ofZeroBased(2)),
            Utf16LineIndex("x😀").toCodePoint(Utf16Position.ofZeroBased(1, 0)),
        )
    }

    private fun checkLaws(text: String) {
        val index = Utf16LineIndex(text)
        val lines = oracleLines(text)
        val context = "text \"${escape(text)}\""
        assertEquals(lines.size - 1, index.lastLine.zeroBased, context)

        val positions = mutableListOf<Utf16Position>()
        lines.forEachIndexed { lineNumber, content ->
            val line = Line.ofZeroBased(lineNumber)
            val boundaries = codePointBoundaries(content)
            val codePoints = boundaries.size - 1
            assertEquals(content.length, index.lineLength(lineNumber), "$context line $lineNumber")

            for (codePoint in 0..codePoints + 2) {
                val where = "$context line $lineNumber code point $codePoint"
                val expected = Utf16Column.ofZeroBased(boundaries[minOf(codePoint, codePoints)])
                val column = CodePointColumn.ofZeroBased(codePoint)
                assertEquals(expected, column.toUtf16(content), where)
                assertEquals(Utf16Position.of(line, expected), index.toUtf16(CodePointPosition.of(line, column)), where)
                if (codePoint <= codePoints) {
                    // Round trip: code point -> UTF-16 -> code point is the identity.
                    assertEquals(column, expected.toCodePoint(content), where)
                }
            }

            for (unit in 0..content.length + 2) {
                val where = "$context line $lineNumber UTF-16 column $unit"
                val clamped = minOf(unit, content.length)
                val expected = CodePointColumn.ofZeroBased(boundaries.count { it in 1..clamped })
                val column = Utf16Column.ofZeroBased(unit)
                assertEquals(expected, column.toCodePoint(content), where)
                assertEquals(
                    CodePointPosition.of(line, expected),
                    index.toCodePoint(Utf16Position.of(line, column)),
                    where,
                )
                // Round trip: UTF-16 -> code point -> UTF-16 lands on the last boundary at or before it.
                val floor = boundaries.last { it <= clamped }
                assertEquals(Utf16Column.ofZeroBased(floor), expected.toUtf16(content), where)
                positions += Utf16Position.of(line, column)
            }
        }
        val beyond = Line.ofZeroBased(lines.size)
        val lastLine = Line.ofZeroBased(lines.size - 1)
        val lastContent = lines.last()
        assertEquals(
            CodePointPosition.of(lastLine, CodePointColumn.ofZeroBased(codePointBoundaries(lastContent).size - 1)),
            index.toCodePoint(Utf16Position.of(beyond, Utf16Column.ofZeroBased(0))),
            context,
        )
        assertEquals(
            Utf16Position.of(lastLine, Utf16Column.ofZeroBased(lastContent.length)),
            index.toUtf16(CodePointPosition.of(beyond, CodePointColumn.ofZeroBased(0))),
            context,
        )
        positions += Utf16Position.of(beyond, Utf16Column.ofZeroBased(0))
        checkSpans(index, positions, context)
    }

    /** Conversion is monotonic, so every ordered pair converts to a valid span, end for end. */
    private fun checkSpans(index: Utf16LineIndex, positions: List<Utf16Position>, context: String) {
        val sorted = positions.sorted()
        for (i in sorted.indices) {
            val start = sorted[i]
            for (end in listOf(sorted[i], sorted[(i + 1).coerceAtMost(sorted.lastIndex)], sorted.last())) {
                val span = index.toCodePoint(Utf16Span.of(start, end))
                assertEquals(index.toCodePoint(start), span.start, context)
                assertEquals(index.toCodePoint(end), span.endExclusive, context)
                val back = index.toUtf16(span)
                assertTrue(back.start <= back.endExclusive, context)
                assertEquals(index.toUtf16(span.start), back.start, context)
            }
        }
    }

    private companion object {
        /** Every unit that interacts with columns or lines: plain, tab, combining, pair, lone halves, breaks. */
        val TOKENS = listOf("a", "\t", "\u0301", "😀", "\uD83D", "\uDE00", "\r", "\n")
        const val EXHAUSTIVE_LENGTH = 5
        const val RANDOM_SEED = 2391L
        const val RANDOM_TEXTS = 300
        const val RANDOM_MAX_TOKENS = 120

        fun forEachText(maxTokens: Int, action: (String) -> Unit) {
            fun extend(prefix: String, remaining: Int) {
                action(prefix)
                if (remaining > 0) TOKENS.forEach { extend(prefix + it, remaining - 1) }
            }
            extend("", maxTokens)
        }

        fun pow(base: Int, exponent: Int): Int = (1..exponent).fold(1) { product, _ -> product * base }

        /** Line contents split on CRLF, bare CR and LF, written independently of [Utf16LineIndex]. */
        fun oracleLines(text: String): List<String> = text.split("\r\n", "\r", "\n")

        /** UTF-16 offsets of every code point boundary in [line], from 0 to its length, using the JDK. */
        fun codePointBoundaries(line: String): List<Int> {
            val boundaries = mutableListOf(0)
            line.codePoints().forEach { boundaries += boundaries.last() + Character.charCount(it) }
            return boundaries
        }

        fun escape(text: String): String = text.map { char ->
            if (char.code in 0x20..0x7E) char.toString() else "\\u%04X".format(char.code)
        }.joinToString("")
    }
}
