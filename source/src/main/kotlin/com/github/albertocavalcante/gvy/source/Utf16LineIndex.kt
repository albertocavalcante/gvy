// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * Line coordinates over a string's UTF-16 code units. LF, CRLF and bare CR end a line.
 * Offsets inside a line break are represented on the preceding line.
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
}
