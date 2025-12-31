package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Postfix expression: `expr++` or `expr--`
 *
 * Example:
 * ```groovy
 * i++
 * count--
 * ```
 */
class PostfixExpr(val expression: Expression, val operator: String) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "PostfixExpr[$operator]"
}
