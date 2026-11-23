// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A half-open stretch of source text from [start] to [endExclusive] in UTF-16 columns, the shape of
 * an LSP range. `start <= endExclusive` holds for every instance; an empty span marks a point.
 *
 * This type defines no containment test: whether a cursor at [endExclusive] is inside a span is a
 * caller's policy (issue #1190 tracks the public contract). The conversion to [CodePointSpan] is
 * [Utf16LineIndex.toCodePoint].
 */
public class Utf16Span internal constructor(
    /** The first position inside the span. */
    public val start: Utf16Position,
    /** The first position after the span. */
    public val endExclusive: Utf16Position,
) {
    init {
        require(start <= endExclusive) { "A span must not end before it starts: $start > $endExclusive" }
    }

    /** True when the span covers no text. */
    public val isEmpty: Boolean get() = start == endExclusive

    /** Spans are equal when both ends are. */
    override fun equals(other: Any?): Boolean =
        other is Utf16Span && start == other.start && endExclusive == other.endExclusive

    /** Consistent with [equals]. */
    override fun hashCode(): Int = HASH_MULTIPLIER * start.hashCode() + endExclusive.hashCode()

    /** Shows both ends. */
    override fun toString(): String = "Utf16Span($start until $endExclusive)"

    /** Factories that check the order of the ends. */
    public companion object {
        private const val HASH_MULTIPLIER = 31

        /** The span from [start] to [endExclusive]; throws [IllegalArgumentException] if it ends before it starts. */
        public fun of(start: Utf16Position, endExclusive: Utf16Position): Utf16Span = Utf16Span(start, endExclusive)

        /** The span from [start] to [endExclusive], or null if it ends before it starts. */
        public fun ofOrNull(start: Utf16Position, endExclusive: Utf16Position): Utf16Span? =
            if (start <= endExclusive) Utf16Span(start, endExclusive) else null

        /** The validated form of [raw], or null if a part is negative or the range is reversed. */
        public fun ofRawOrNull(raw: RawUtf16Range): Utf16Span? {
            val start = Utf16Position.ofRawOrNull(raw.start) ?: return null
            val endExclusive = Utf16Position.ofRawOrNull(raw.endExclusive) ?: return null
            return ofOrNull(start, endExclusive)
        }
    }
}
