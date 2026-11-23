// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

import java.util.BitSet

/**
 * Line coordinates over a string's UTF-16 code units. LF, CRLF and bare CR end a line.
 * Offsets inside a line break are represented on the preceding line.
 *
 * The typed members convert between [Utf16Position] and [CodePointPosition] on these lines. A line
 * here is a line of this index, not a Groovy compiler line: the compiler ends lines only at LF, so
 * on text with a bare CR the two disagree. Do not convert compiler coordinates through this index
 * until the compiler line model is decided (issue #2413).
 *
 * Column rules, the same in every typed member: a column past the end of its line becomes the
 * line's end, as LSP specifies for a character past the line length; a UTF-16 column between the
 * halves of a surrogate pair becomes the column of that pair's code point. A position inside a
 * CRLF therefore stays on its line, at the line's end.
 *
 * Line rules differ by form. A line after [lastLine] is not in the text; LSP does not define it.
 * The strict forms ([toCodePointOrNull], [toUtf16OrNull], [lineLength]) return null for it, and
 * [contains] is false. The clamping forms ([toCodePoint], [toUtf16]) map it to the end of the text,
 * which is undetectable afterwards: use them only where any in-text answer is acceptable.
 *
 * A line without surrogates converts in constant time (code units and code points coincide); a
 * line with one walks from its start, in time linear in the column. The untyped members keep their
 * historical contracts.
 */
public class Utf16LineIndex(private val text: String) {
    private val lineStarts: IntArray = run {
        val starts = arrayListOf(0)
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char == '\r' || char == '\n') {
                if (char == '\r' && at + 1 < text.length && text[at + 1] == '\n') at++
                starts += at + 1
            }
            at++
        }
        starts.toIntArray()
    }
    private val lineLengths = IntArray(lineStarts.size) { line ->
        val start = lineStarts[line]
        val beforeBreak = lineStarts.getOrNull(line + 1)?.minus(1) ?: text.length
        val end = if (beforeBreak > start && text[beforeBreak - 1] == '\r') beforeBreak - 1 else beforeBreak
        end - start
    }

    /** One bit per line: set when the line's content has any surrogate, paired or not. */
    private val linesWithSurrogates = BitSet(lineStarts.size).also { lines ->
        for (line in lineStarts.indices) {
            val start = lineStarts[line]
            for (at in start until start + lineLengths[line]) {
                if (Character.isSurrogate(text[at])) {
                    lines.set(line)
                    break
                }
            }
        }
    }

    /**
     * Returns the offset for a zero-based coordinate. Columns may cross line boundaries and are
     * clamped only to the document end. A line past the last returns the document end.
     * A negative line throws; a negative column is not clamped, and integer addition may overflow.
     */
    public fun offsetOf(line: Int, character: Int): Int =
        if (line >= lineStarts.size) text.length else minOf(lineStarts[line] + character, text.length)

    /**
     * Returns the line containing an offset, including offsets within line breaks. Negative offsets
     * return the first line and offsets past the document return the last line.
     */
    public fun lineAt(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Returns the absolute offset where a line starts; an invalid line throws. */
    public fun lineStart(line: Int): Int = lineStarts[line]

    /** Returns the length excluding the line break, or null for a nonexistent line. */
    public fun lineLength(line: Int): Int? = lineLengths.getOrNull(line)

    /** Checks whether a coordinate lies within a line's content or at its end. */
    public fun isValidPosition(line: Int, character: Int): Boolean =
        lineLength(line)?.let { character in 0..it } ?: false

    /** The number of lines; at least 1, because an empty text has one empty line. */
    public val lineCount: Int get() = lineStarts.size

    /** The last line of the text; a text ending in a line break has an empty last line. */
    public val lastLine: Line get() = Line(lineStarts.size - 1)

    /** The length of [line] without its line break, in UTF-16 columns; null for a line after [lastLine]. */
    public fun lineLength(line: Line): Utf16Column? = lineLengths.getOrNull(line.zeroBased)?.let(::Utf16Column)

    /** True when [position]'s line is in the text and its column is within the line or at its end. */
    public fun contains(position: Utf16Position): Boolean {
        val length = lineLengths.getOrNull(position.line.zeroBased) ?: return false
        return position.column.zeroBased <= length
    }

    /** True when [position]'s line is in the text and its column is within the line or at its end. */
    public fun contains(position: CodePointPosition): Boolean {
        val line = position.line.zeroBased
        if (line >= lineStarts.size) return false
        return position.column.zeroBased <= codePointColumnOn(line, Utf16Column.MAX_ZERO_BASED)
    }

    /** The same place in code-point columns, or null for a line after [lastLine]; the column clamps. */
    public fun toCodePointOrNull(position: Utf16Position): CodePointPosition? =
        if (position.line.zeroBased < lineStarts.size) toCodePoint(position) else null

    /** The same place in UTF-16 columns, or null for a line after [lastLine]; the column clamps. */
    public fun toUtf16OrNull(position: CodePointPosition): Utf16Position? =
        if (position.line.zeroBased < lineStarts.size) toUtf16(position) else null

    /** The same place in code-point columns; a line after [lastLine] becomes the end of the text. */
    public fun toCodePoint(position: Utf16Position): CodePointPosition {
        // Unboxed on every path: the strict form delegates here, never the other way round.
        if (position.line.zeroBased >= lineStarts.size) {
            val endOfText = codePointColumnOn(lastLine.zeroBased, Utf16Column.MAX_ZERO_BASED)
            return CodePointPosition.of(lastLine, CodePointColumn(endOfText))
        }
        val column = codePointColumnOn(position.line.zeroBased, position.column.zeroBased)
        return CodePointPosition.of(position.line, CodePointColumn(column))
    }

    /** The same place in UTF-16 columns; a line after [lastLine] becomes the end of the text. */
    public fun toUtf16(position: CodePointPosition): Utf16Position {
        if (position.line.zeroBased >= lineStarts.size) {
            return Utf16Position.of(lastLine, Utf16Column(lineLengths.last()))
        }
        val column = utf16ColumnOn(position.line.zeroBased, position.column.zeroBased)
        return Utf16Position.of(position.line, Utf16Column(column))
    }

    /**
     * Both ends of [span] in code-point columns, each clamped as [toCodePoint] does. Clamping keeps
     * their order, so the result is a valid span. For strict handling, convert each end with
     * [toCodePointOrNull].
     */
    public fun toCodePoint(span: Utf16Span): CodePointSpan =
        CodePointSpan(toCodePoint(span.start), toCodePoint(span.endExclusive))

    /** Both ends of [span] in UTF-16 columns, each clamped as [toUtf16] does; see [toCodePoint]. */
    public fun toUtf16(span: CodePointSpan): Utf16Span = Utf16Span(toUtf16(span.start), toUtf16(span.endExclusive))

    /** The conversion used on [line]: identity for a line without surrogates, otherwise a walk. */
    internal fun columnUnitsOf(line: Int): ColumnUnits =
        if (linesWithSurrogates[line]) ColumnUnits.Walking else ColumnUnits.Identity

    private fun codePointColumnOn(line: Int, utf16: Int): Int {
        val start = lineStarts[line]
        return columnUnitsOf(line).codePointColumn(text, start, start + lineLengths[line], utf16)
    }

    private fun utf16ColumnOn(line: Int, codePoints: Int): Int {
        val start = lineStarts[line]
        return columnUnitsOf(line).utf16Column(text, start, start + lineLengths[line], codePoints)
    }
}
