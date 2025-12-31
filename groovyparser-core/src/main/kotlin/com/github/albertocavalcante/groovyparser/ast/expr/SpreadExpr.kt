package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Spread operator expression: `*list`
 * Spreads a collection's elements as separate arguments.
 *
 * Example:
 * ```groovy
 * def list = [1, 2, 3]
 * method(*list)  // Equivalent to method(1, 2, 3)
 * ```
 */
class SpreadExpr(val expression: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "SpreadExpr"
}
