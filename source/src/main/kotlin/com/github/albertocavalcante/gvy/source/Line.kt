// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A line of a source text, as delimited by [Utf16LineIndex] (LF, CRLF and bare CR end a line).
 *
 * The value is stored zero-based. The base is named only where an [Int] enters or leaves the type
 * ([ofZeroBased], [ofOneBased], [zeroBased], [oneBased]), so a line cannot be passed with the
 * wrong base, and no caller adds or subtracts one by hand. Whether the line exists in a particular
 * text is decided by the index that interprets it, not by this type.
 */
@JvmInline
public value class Line internal constructor(
    /** The zero-based line number: 0 is the first line. */
    public val zeroBased: Int,
) : Comparable<Line> {
    init {
        require(zeroBased in 0..MAX_ZERO_BASED) { "A zero-based line must be in 0..$MAX_ZERO_BASED, got $zeroBased" }
    }

    /** The one-based line number: 1 is the first line. Never overflows. */
    public val oneBased: Int get() = zeroBased + 1

    /** Orders lines from the start of the text. */
    override fun compareTo(other: Line): Int = zeroBased.compareTo(other.zeroBased)

    /** Shows both bases, so a log line cannot be misread. */
    override fun toString(): String = "Line(zeroBased=$zeroBased, oneBased=$oneBased)"

    /** Factories that name the base of the [Int] they accept. */
    public companion object {
        /** The largest zero-based line; one less than [Int.MAX_VALUE] so that [oneBased] fits in an [Int]. */
        public const val MAX_ZERO_BASED: Int = Int.MAX_VALUE - 1

        /** The line with zero-based number [value]; throws [IllegalArgumentException] outside 0..[MAX_ZERO_BASED]. */
        public fun ofZeroBased(value: Int): Line = Line(value)

        /** The line with one-based number [value]; throws [IllegalArgumentException] below 1. */
        public fun ofOneBased(value: Int): Line {
            require(value >= 1) { "A one-based line must be at least 1, got $value" }
            return Line(value - 1)
        }

        /** The line with zero-based number [value], or null outside 0..[MAX_ZERO_BASED] (for unvalidated input). */
        public fun ofZeroBasedOrNull(value: Int): Line? = if (value in 0..MAX_ZERO_BASED) Line(value) else null

        /** The line with one-based number [value], or null below 1 (for example a compiler's synthetic -1). */
        public fun ofOneBasedOrNull(value: Int): Line? = if (value >= 1) Line(value - 1) else null
    }
}
