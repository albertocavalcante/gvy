// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A column counted in Unicode code points from the start of its line, LSP's `utf-32` position
 * encoding. A Groovy compiler column is not one of these by assumption: compiler lines differ
 * (issue #2413) and some compiler endpoints are not code points (issue #1190).
 *
 * The value is stored zero-based; the base is named only where an [Int] enters or leaves the type.
 * A supplementary character (a surrogate pair) is one column; a combining mark and a tab are one
 * column each, so this is neither a grapheme nor a display column. An unpaired surrogate counts as
 * one code point, as in [String.codePointCount]. Changing to [Utf16Column] needs the line text
 * ([toUtf16]) or a [Utf16LineIndex]; there is no arithmetic conversion.
 */
@JvmInline
public value class CodePointColumn internal constructor(
    /** The zero-based column: 0 is before the first code point of the line. */
    public val zeroBased: Int,
) : Comparable<CodePointColumn> {
    init {
        require(zeroBased in 0..MAX_ZERO_BASED) {
            "A zero-based code-point column must be in 0..$MAX_ZERO_BASED, got $zeroBased"
        }
    }

    /** The one-based column (1 is before the first code point), Never overflows. */
    public val oneBased: Int get() = zeroBased + 1

    /**
     * The same place in UTF-16 code units on [lineText], the content of one line without its line
     * break. A column past the last code point of [lineText] becomes its end. Line break characters
     * inside [lineText] are counted as ordinary characters.
     */
    public fun toUtf16(lineText: CharSequence): Utf16Column =
        Utf16Column(ColumnUnits.Walking.utf16Column(lineText, 0, lineText.length, zeroBased))

    /** Orders columns from the start of the line. */
    override fun compareTo(other: CodePointColumn): Int = zeroBased.compareTo(other.zeroBased)

    /** Shows the unit and both bases. */
    override fun toString(): String = "CodePointColumn(zeroBased=$zeroBased, oneBased=$oneBased)"

    /** Factories that name the base of the [Int] they accept. */
    public companion object {
        /** The largest zero-based column; one less than [Int.MAX_VALUE] so that [oneBased] fits in an [Int]. */
        public const val MAX_ZERO_BASED: Int = Int.MAX_VALUE - 1

        /** The column [value] code points from the line start; throws [IllegalArgumentException] if out of range. */
        public fun ofZeroBased(value: Int): CodePointColumn = CodePointColumn(value)

        /** The one-based column [value]; throws [IllegalArgumentException] below 1. */
        public fun ofOneBased(value: Int): CodePointColumn {
            require(value >= 1) { "A one-based code-point column must be at least 1, got $value" }
            return CodePointColumn(value - 1)
        }

        /** The zero-based column [value], or null outside 0..[MAX_ZERO_BASED] (for unvalidated input). */
        public fun ofZeroBasedOrNull(value: Int): CodePointColumn? =
            if (value in 0..MAX_ZERO_BASED) CodePointColumn(value) else null

        /** The one-based column [value], or null below 1. */
        public fun ofOneBasedOrNull(value: Int): CodePointColumn? = if (value >= 1) CodePointColumn(value - 1) else null
    }
}
