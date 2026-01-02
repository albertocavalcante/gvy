package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Prefix expression: `++expr` or `--expr`
 *
 * Example:
 * ```groovy
 * ++i
 * --count
 * ```
 */
class PrefixExpr(val operator: String, val expression: Expression) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "PrefixExpr[$operator]"
}
