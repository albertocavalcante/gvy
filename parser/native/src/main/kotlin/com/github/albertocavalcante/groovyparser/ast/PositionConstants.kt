package com.github.albertocavalcante.groovyparser.ast

/**
 * Constants for position calculations and node prioritization.
 * Extracted from PositionAwareVisitor to eliminate magic numbers.
 */
object PositionConstants {
    const val MULTI_LINE_WEIGHT = 100
    const val LINE_WEIGHT = 1000
    const val INVALID_POSITION = -1
    const val MAX_RANGE_SIZE = Int.MAX_VALUE
}
