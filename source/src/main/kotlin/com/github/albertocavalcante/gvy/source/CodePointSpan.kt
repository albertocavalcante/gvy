// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A half-open stretch of source text from [start] to [endExclusive] in code-point columns.
 * `start <= endExclusive` holds for every instance; an empty span marks a point.
 *
 * This type defines no containment test (see [Utf16Span]). Groovy AST end columns are not proven to
 * be exclusive code-point columns for every node (issue #1190), so building one from a compiler
 * node is a provider decision, not something this type certifies. The conversion to [Utf16Span] is
 * [Utf16LineIndex.toUtf16].
 */
public class CodePointSpan internal constructor(
    /** The first position inside the span. */
    public val start: CodePointPosition,
    /** The first position after the span. */
    public val endExclusive: CodePointPosition,
) {
    init {
        require(start <= endExclusive) { "A span must not end before it starts: $start > $endExclusive" }
    }

    /** True when the span covers no text. */
    public val isEmpty: Boolean get() = start == endExclusive

    /** Spans are equal when both ends are. */
    override fun equals(other: Any?): Boolean =
        other is CodePointSpan && start == other.start && endExclusive == other.endExclusive

    /** Consistent with [equals]. */
    override fun hashCode(): Int = HASH_MULTIPLIER * start.hashCode() + endExclusive.hashCode()

    /** Shows both ends. */
    override fun toString(): String = "CodePointSpan($start until $endExclusive)"

    /** Factories that check the order of the ends. */
    public companion object {
        private const val HASH_MULTIPLIER = 31

        /** The span from [start] to [endExclusive]; throws [IllegalArgumentException] if it ends before it starts. */
        public fun of(start: CodePointPosition, endExclusive: CodePointPosition): CodePointSpan =
            CodePointSpan(start, endExclusive)

        /** The span from [start] to [endExclusive], or null if it ends before it starts. */
        public fun ofOrNull(start: CodePointPosition, endExclusive: CodePointPosition): CodePointSpan? =
            if (start <= endExclusive) CodePointSpan(start, endExclusive) else null
    }
}
