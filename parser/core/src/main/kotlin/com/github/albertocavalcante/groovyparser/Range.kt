package com.github.albertocavalcante.groovyparser

/**
 * A range in a source file, defined by a begin and end position.
 *
 * Positions are Groovy's 1-based line/column pairs. The begin is inclusive and the end is **exclusive**: it is
 * the position just past the last character, exactly Groovy's `lastLineNumber`/`lastColumnNumber`.
 */
data class Range(val begin: Position, val end: Position) {
    /**
     * Checks if this range contains the given position.
     * A position is contained if it is >= begin and < end (so an empty range contains nothing).
     */
    fun contains(position: Position): Boolean = position >= begin && position < end

    /**
     * Checks if this range contains the given range.
     * A range is contained if it starts at or after this begin and ends at or before this (exclusive) end.
     */
    fun contains(range: Range): Boolean = range.begin >= begin && range.end <= end

    override fun toString(): String = "Range[$begin - $end]"

    companion object {
        /**
         * Creates a range from line/column coordinates.
         */
        fun range(beginLine: Int, beginColumn: Int, endLine: Int, endColumn: Int): Range = Range(
            Position(beginLine, beginColumn),
            Position(endLine, endColumn),
        )
    }
}
