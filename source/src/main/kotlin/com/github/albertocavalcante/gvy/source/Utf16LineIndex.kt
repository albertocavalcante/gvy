// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * Line coordinates over a string's UTF-16 code units. LF, CRLF and bare CR end a line.
 * Offsets inside a line break are represented on the preceding line.
 *
 * The typed members ([toCodePoint], [toUtf16]) are the converter between [Utf16Position] and
 * [CodePointPosition]. They are total and clamp, as LSP specifies for a character past a line's
 * length: a column past the end of its line becomes that line's end, and a line after [lastLine]
 * becomes the end of the text. A UTF-16 column between the halves of a surrogate pair becomes the
 * column of that pair's code point. The line itself never changes otherwise, so a position inside
 * a CRLF stays on its line, at the line's end. The untyped members keep their historical contracts.
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

    /** The last line of the text; a text ending in a line break has an empty last line. */
    public val lastLine: Line get() = Line(lineStarts.size - 1)

    /** The same place in code-point columns, clamped as the class documentation describes. */
    public fun toCodePoint(position: Utf16Position): CodePointPosition {
        val line = position.line.zeroBased
        if (line >= lineStarts.size) return endInCodePoints()
        val start = lineStarts[line]
        val column = ColumnUnits.codePointColumn(text, start, start + lineLengths[line], position.column.zeroBased)
        return CodePointPosition.of(position.line, CodePointColumn(column))
    }

    /** The same place in UTF-16 columns, clamped as the class documentation describes. */
    public fun toUtf16(position: CodePointPosition): Utf16Position {
        val line = position.line.zeroBased
        if (line >= lineStarts.size) return Utf16Position.of(lastLine, Utf16Column(lineLengths.last()))
        val start = lineStarts[line]
        val column = ColumnUnits.utf16Column(text, start, start + lineLengths[line], position.column.zeroBased)
        return Utf16Position.of(position.line, Utf16Column(column))
    }

    /** Both ends of [span] in code-point columns. Clamping keeps their order, so the result is a valid span. */
    public fun toCodePoint(span: Utf16Span): CodePointSpan =
        CodePointSpan(toCodePoint(span.start), toCodePoint(span.endExclusive))

    /** Both ends of [span] in UTF-16 columns. Clamping keeps their order, so the result is a valid span. */
    public fun toUtf16(span: CodePointSpan): Utf16Span = Utf16Span(toUtf16(span.start), toUtf16(span.endExclusive))

    private fun endInCodePoints(): CodePointPosition {
        val last = lineStarts.size - 1
        val start = lineStarts[last]
        val end = start + lineLengths[last]
        return CodePointPosition.of(
            lastLine,
            CodePointColumn(ColumnUnits.codePointColumn(text, start, end, end - start)),
        )
    }
}
