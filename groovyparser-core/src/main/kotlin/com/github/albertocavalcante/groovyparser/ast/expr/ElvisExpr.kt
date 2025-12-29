package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Elvis operator expression: `a ?: b`
 * Returns `a` if it's truthy, otherwise `b`.
 *
 * Example:
 * ```groovy
 * def name = input ?: "default"
 * ```
 */
class ElvisExpr(val expression: Expression, val defaultValue: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
        setAsParentNodeOf(defaultValue)
    }

    override fun getChildNodes(): List<Node> = listOf(expression, defaultValue)

    override fun toString(): String = "ElvisExpr"
}
