package com.github.albertocavalcante.groovyparser.api.model

/**
 * A position in a source file (1-based line and column).
 */
data class Position(
    /** 1-based line number. */
    val line: Int,
    /** 1-based column number. */
    val column: Int,
) : Comparable<Position> {
    override fun compareTo(other: Position): Int {
        val lineDiff = line.compareTo(other.line)
        return if (lineDiff != 0) lineDiff else column.compareTo(other.column)
    }

    companion object {
        /** A sentinel value representing an invalid or uninitialized position. */
        val ZERO = Position(0, 0)

        /** The starting position in a file (line 1, column 1). */
        val START = Position(1, 1)
    }
}

/**
 * A range in a source file (start and end positions).
 */
data class Range(val start: Position, val end: Position) {
    fun contains(position: Position): Boolean = position >= start && position <= end

    companion object {
        val EMPTY = Range(Position.ZERO, Position.ZERO)
    }
}
