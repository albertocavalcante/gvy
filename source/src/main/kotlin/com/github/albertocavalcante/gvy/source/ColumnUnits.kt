// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * Column unit conversion over the line `text[start, end)`. Both directions clamp to the line end
 * and never allocate. [Walking] is the one general implementation; [Identity] is its exact
 * shortcut for a line known to contain no surrogate, where code units and code points coincide.
 */
internal sealed interface ColumnUnits {
    /** Code points wholly before [utf16] code units of the line; a split surrogate pair is not counted. */
    fun codePointColumn(text: CharSequence, start: Int, end: Int, utf16: Int): Int

    /** Code units before the first [codePoints] code points of the line, or the line length if it has fewer. */
    fun utf16Column(text: CharSequence, start: Int, end: Int, codePoints: Int): Int

    /** For a line without surrogates: constant time. Wrong for any other line. */
    data object Identity : ColumnUnits {
        override fun codePointColumn(text: CharSequence, start: Int, end: Int, utf16: Int): Int =
            minOf(utf16, end - start)

        override fun utf16Column(text: CharSequence, start: Int, end: Int, codePoints: Int): Int =
            minOf(codePoints, end - start)
    }

    /**
     * For any line: walks from its start, in time linear in the column. A surrogate pair is one code
     * point only when both halves lie inside the line; an unpaired surrogate is one code point, as in
     * [String.codePointCount].
     */
    data object Walking : ColumnUnits {
        override fun codePointColumn(text: CharSequence, start: Int, end: Int, utf16: Int): Int {
            val target = start + minOf(utf16, end - start)
            var at = start
            var codePoints = 0
            while (at < target) {
                val next = at + width(text, at, end)
                if (next > target) break
                at = next
                codePoints++
            }
            return codePoints
        }

        override fun utf16Column(text: CharSequence, start: Int, end: Int, codePoints: Int): Int {
            var at = start
            var remaining = codePoints
            while (remaining > 0 && at < end) {
                at += width(text, at, end)
                remaining--
            }
            return at - start
        }

        /** The UTF-16 width of the code point at [at]: 2 for a surrogate pair inside the line, else 1. */
        private fun width(text: CharSequence, at: Int, end: Int): Int {
            val isPair =
                Character.isHighSurrogate(text[at]) &&
                    at + 1 < end &&
                    Character.isLowSurrogate(text[at + 1])
            return if (isPair) 2 else 1
        }
    }
}
