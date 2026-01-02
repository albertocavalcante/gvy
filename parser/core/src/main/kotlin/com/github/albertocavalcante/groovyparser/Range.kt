package com.github.albertocavalcante.groovyparser

/**
 * A range in a source file, defined by a begin and end position.
 * The range is inclusive on both ends.
 */
data class Range(val begin: Position, val end: Position) {
    /**
     * Checks if this range contains the given position.
     * A position is contained if it is >= begin and <= end.
     */
    fun contains(position: Position): Boolean = position >= begin && position <= end

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
