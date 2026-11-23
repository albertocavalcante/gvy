// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

/**
 * A place in a source text: a [Line] and a [CodePointColumn] on it.
 *
 * The line is delimited as by [Utf16LineIndex] (LF, CRLF and bare CR). The language server models
 * Groovy compiler lines as ending only at LF, so read a compiler line and column as this position
 * only for text without a bare CR.
 *
 * Both parts are packed into one [Long], so a position is never allocated where its static type is
 * [CodePointPosition]. Positions order by line, then column. The conversion to [Utf16Position] is
 * [Utf16LineIndex.toUtf16].
 */
@JvmInline
public value class CodePointPosition private constructor(private val packed: Long) : Comparable<CodePointPosition> {
    /** The line of this position. */
    public val line: Line get() = Line(PackedCoordinates.line(packed))

    /** The code-point column of this position on [line]. */
    public val column: CodePointColumn get() = CodePointColumn(PackedCoordinates.column(packed))

    /** Orders by line, then column. */
    override fun compareTo(other: CodePointPosition): Int = packed.compareTo(other.packed)

    /** Shows zero-based line and code-point column. */
    override fun toString(): String = "CodePointPosition(line=${line.zeroBased}, column=${column.zeroBased} [0-based])"

    /** Factories from typed parts, or from one-based integers such as a Groovy AST node's. */
    public companion object {
        /** The position at [column] on [line]. */
        public fun of(line: Line, column: CodePointColumn): CodePointPosition =
            CodePointPosition(PackedCoordinates.pack(line.zeroBased, column.zeroBased))

        /** The position at one-based [line] and code-point [column]; throws [IllegalArgumentException] below 1. */
        public fun ofOneBased(line: Int, column: Int): CodePointPosition =
            of(Line.ofOneBased(line), CodePointColumn.ofOneBased(column))
    }
}
