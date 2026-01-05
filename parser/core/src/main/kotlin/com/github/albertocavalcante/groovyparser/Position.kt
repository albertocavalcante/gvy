package com.github.albertocavalcante.groovyparser

/**
 * A position in a source file, consisting of a line number and column number.
 * Both line and column are 1-based (first line is 1, first column is 1).
 */
data class Position(val line: Int, val column: Int) : Comparable<Position> {

    override fun compareTo(other: Position): Int {
        val lineComparison = line.compareTo(other.line)
        return if (lineComparison != 0) lineComparison else column.compareTo(other.column)
    }

    override fun toString(): String = "(line $line, col $column)"

    companion object {
        /** The first line in a source file (1-based). */
        const val FIRST_LINE = 1

        /** The first column in a source file (1-based). */
        const val FIRST_COLUMN = 1

        /** The home position: line 1, column 1. */
        val HOME = Position(FIRST_LINE, FIRST_COLUMN)
    }
}
