// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A place in a source text: a [Line] and a [Utf16Column] on it, the shape of an LSP position in the
 * default `utf-16` encoding.
 *
 * Both parts are packed into one [Long], so a position is never allocated where its static type is
 * [Utf16Position]. Positions order by line, then column. Whether the position exists in a
 * particular text is decided by the [Utf16LineIndex] that interprets it; the conversion to
 * [CodePointPosition] is [Utf16LineIndex.toCodePoint].
 */
@JvmInline
public value class Utf16Position private constructor(private val packed: Long) : Comparable<Utf16Position> {
    /** The line of this position. */
    public val line: Line get() = Line(PackedCoordinates.line(packed))

    /** The UTF-16 column of this position on [line]. */
    public val column: Utf16Column get() = Utf16Column(PackedCoordinates.column(packed))

    /** Orders by line, then column. */
    override fun compareTo(other: Utf16Position): Int = packed.compareTo(other.packed)

    /** Shows zero-based line and UTF-16 column. */
    override fun toString(): String = "Utf16Position(line=${line.zeroBased}, column=${column.zeroBased} [0-based])"

    /** Factories from typed parts, or from zero-based integers such as an LSP position's. */
    public companion object {
        /** The position at [column] on [line]. */
        public fun of(line: Line, column: Utf16Column): Utf16Position =
            Utf16Position(PackedCoordinates.pack(line.zeroBased, column.zeroBased))

        /** The position at zero-based [line] and UTF-16 [column]; throws [IllegalArgumentException] if negative. */
        public fun ofZeroBased(line: Int, column: Int): Utf16Position =
            of(Line.ofZeroBased(line), Utf16Column.ofZeroBased(column))

        /** The validated form of [raw], or null if either part is negative (or [Int.MAX_VALUE]). */
        public fun ofRawOrNull(raw: RawUtf16Position): Utf16Position? {
            val isRepresentable =
                raw.line in 0..Line.MAX_ZERO_BASED &&
                    raw.character in 0..Utf16Column.MAX_ZERO_BASED
            return if (isRepresentable) of(Line(raw.line), Utf16Column(raw.character)) else null
        }
    }
}

/** Packs a nonnegative line and column into one [Long] whose natural order is (line, column). */
internal object PackedCoordinates {
    private const val COLUMN_BITS = 32
    private const val COLUMN_MASK = 0xFFFF_FFFFL

    fun pack(line: Int, column: Int): Long = (line.toLong() shl COLUMN_BITS) or column.toLong()

    fun line(packed: Long): Int = (packed ushr COLUMN_BITS).toInt()

    fun column(packed: Long): Int = (packed and COLUMN_MASK).toInt()
}
