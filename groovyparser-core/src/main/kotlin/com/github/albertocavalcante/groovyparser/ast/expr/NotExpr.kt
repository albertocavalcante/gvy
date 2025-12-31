package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Logical negation expression: `!expr`
 *
 * Example:
 * ```groovy
 * if (!isEmpty) { ... }
 * def valid = !errors
 * ```
 */
class NotExpr(val expression: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "NotExpr"
}
