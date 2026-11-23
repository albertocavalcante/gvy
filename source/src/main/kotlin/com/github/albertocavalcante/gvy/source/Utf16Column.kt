// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A column counted in UTF-16 code units from the start of its line, the unit of LSP's default
 * `utf-16` position encoding and of Kotlin/Java string indexes.
 *
 * The value is stored zero-based; the base is named only where an [Int] enters or leaves the type.
 * A surrogate pair occupies two columns, and a column may fall between its halves. Changing to
 * [CodePointColumn] needs the line text ([toCodePoint]) or a [Utf16LineIndex]; there is no
 * arithmetic conversion.
 */
@JvmInline
public value class Utf16Column internal constructor(
    /** The zero-based column: 0 is before the first code unit of the line. */
    public val zeroBased: Int,
) : Comparable<Utf16Column> {
    init {
        require(zeroBased in 0..MAX_ZERO_BASED) {
            "A zero-based UTF-16 column must be in 0..$MAX_ZERO_BASED, got $zeroBased"
        }
    }

    /** The one-based column (1 is before the first code unit), as SARIF reports it. Never overflows. */
    public val oneBased: Int get() = zeroBased + 1

    /**
     * The same place in code points on [lineText], the content of one line without its line break.
     * A column past the end of [lineText] becomes its end; a column between the two halves of a
     * surrogate pair becomes the column of that pair's code point (the one before the split). Line
     * break characters inside [lineText] are counted as ordinary characters.
     */
    public fun toCodePoint(lineText: CharSequence): CodePointColumn =
        CodePointColumn(ColumnUnits.codePointColumn(lineText, 0, lineText.length, zeroBased))

    /** Orders columns from the start of the line. */
    override fun compareTo(other: Utf16Column): Int = zeroBased.compareTo(other.zeroBased)

    /** Shows the unit and both bases. */
    override fun toString(): String = "Utf16Column(zeroBased=$zeroBased, oneBased=$oneBased)"

    /** Factories that name the base of the [Int] they accept. */
    public companion object {
        /** The largest zero-based column; one less than [Int.MAX_VALUE] so that [oneBased] fits in an [Int]. */
        public const val MAX_ZERO_BASED: Int = Int.MAX_VALUE - 1

        /** The column [value] code units from the line start; throws [IllegalArgumentException] if out of range. */
        public fun ofZeroBased(value: Int): Utf16Column = Utf16Column(value)

        /** The one-based column [value]; throws [IllegalArgumentException] below 1. */
        public fun ofOneBased(value: Int): Utf16Column {
            require(value >= 1) { "A one-based UTF-16 column must be at least 1, got $value" }
            return Utf16Column(value - 1)
        }

        /** The zero-based column [value], or null outside 0..[MAX_ZERO_BASED] (for unvalidated input). */
        public fun ofZeroBasedOrNull(value: Int): Utf16Column? =
            if (value in 0..MAX_ZERO_BASED) Utf16Column(value) else null

        /** The one-based column [value], or null below 1. */
        public fun ofOneBasedOrNull(value: Int): Utf16Column? = if (value >= 1) Utf16Column(value - 1) else null
    }
}
