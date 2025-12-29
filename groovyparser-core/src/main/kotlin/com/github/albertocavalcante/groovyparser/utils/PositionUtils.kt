package com.github.albertocavalcante.groovyparser.utils

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Utility functions for working with positions and ranges in the AST.
 *
 * Similar to JavaParser's PositionUtils.
 */
object PositionUtils {

    /**
     * Returns true if node A is entirely before node B in source order.
     */
    fun areInOrder(a: Node, b: Node): Boolean {
        val rangeA = a.range ?: return false
        val rangeB = b.range ?: return false
        return rangeA.end.isBefore(rangeB.begin) || rangeA.end == rangeB.begin
    }

    /**
     * Returns true if the outer node contains the inner node.
     */
    fun nodeContains(outer: Node, inner: Node): Boolean {
        val outerRange = outer.range ?: return false
        val innerRange = inner.range ?: return false
        return rangeContains(outerRange, innerRange)
    }

    /**
     * Returns true if the outer range fully contains the inner range.
     */
    fun rangeContains(outer: Range, inner: Range): Boolean =
        (outer.begin.isBefore(inner.begin) || outer.begin == inner.begin) &&
            (outer.end.isAfter(inner.end) || outer.end == inner.end)

    /**
     * Returns true if the two ranges overlap.
     */
    fun rangesOverlap(a: Range, b: Range): Boolean = !(a.end.isBefore(b.begin) || b.end.isBefore(a.begin))

    /**
     * Returns true if position is within the range (inclusive).
     */
    fun isInRange(position: Position, range: Range): Boolean =
        (position.isAfter(range.begin) || position == range.begin) &&
            (position.isBefore(range.end) || position == range.end)

    /**
     * Sorts nodes by their begin position.
     */
    fun <T : Node> sortByBeginPosition(nodes: List<T>): List<T> = nodes.sortedWith { a, b ->
        val rangeA = a.range
        val rangeB = b.range
        when {
            rangeA == null && rangeB == null -> 0
            rangeA == null -> 1
            rangeB == null -> -1
            else -> comparePositions(rangeA.begin, rangeB.begin)
        }
    }

    /**
     * Sorts nodes by their end position.
     */
    fun <T : Node> sortByEndPosition(nodes: List<T>): List<T> = nodes.sortedWith { a, b ->
        val rangeA = a.range
        val rangeB = b.range
        when {
            rangeA == null && rangeB == null -> 0
            rangeA == null -> 1
            rangeB == null -> -1
            else -> comparePositions(rangeA.end, rangeB.end)
        }
    }

    /**
     * Compares two positions. Returns negative if a < b, 0 if equal, positive if a > b.
     */
    fun comparePositions(a: Position, b: Position): Int {
        val lineCompare = a.line.compareTo(b.line)
        return if (lineCompare != 0) lineCompare else a.column.compareTo(b.column)
    }

    /**
     * Returns the node that starts first among the given nodes.
     */
    fun <T : Node> findFirstNode(nodes: Collection<T>): T? = nodes.minWithOrNull { a, b ->
        val rangeA = a.range
        val rangeB = b.range
        when {
            rangeA == null && rangeB == null -> 0
            rangeA == null -> 1
            rangeB == null -> -1
            else -> comparePositions(rangeA.begin, rangeB.begin)
        }
    }

    /**
     * Returns the node that ends last among the given nodes.
     */
    fun <T : Node> findLastNode(nodes: Collection<T>): T? = nodes.maxWithOrNull { a, b ->
        val rangeA = a.range
        val rangeB = b.range
        when {
            rangeA == null && rangeB == null -> 0
            rangeA == null -> -1
            rangeB == null -> 1
            else -> comparePositions(rangeA.end, rangeB.end)
        }
    }

    /**
     * Calculates a range that encompasses all given nodes.
     */
    fun encompassingRange(nodes: Collection<Node>): Range? {
        if (nodes.isEmpty()) return null
        val nodesWithRange = nodes.filter { it.range != null }
        if (nodesWithRange.isEmpty()) return null

        val first = findFirstNode(nodesWithRange) ?: return null
        val last = findLastNode(nodesWithRange) ?: return null

        return Range(first.range!!.begin, last.range!!.end)
    }
}

/**
 * Extension function to check if this position is before another.
 */
fun Position.isBefore(other: Position): Boolean = line < other.line || (line == other.line && column < other.column)

/**
 * Extension function to check if this position is after another.
 */
fun Position.isAfter(other: Position): Boolean = line > other.line || (line == other.line && column > other.column)

/**
 * Extension function to check if this position is before or equal to another.
 */
fun Position.isBeforeOrEqual(other: Position): Boolean = this == other || isBefore(other)

/**
 * Extension function to check if this position is after or equal to another.
 */
fun Position.isAfterOrEqual(other: Position): Boolean = this == other || isAfter(other)
