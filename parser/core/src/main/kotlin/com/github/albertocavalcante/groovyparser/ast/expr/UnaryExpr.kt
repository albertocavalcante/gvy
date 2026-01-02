package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a unary expression: `!expr`, `-expr`, `++expr`, `expr++`, etc.
 */
class UnaryExpr(val expression: Expression, val operator: String, val isPrefix: Boolean = true) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = if (isPrefix) {
        "UnaryExpr[$operator expr]"
    } else {
        "UnaryExpr[expr $operator]"
    }
}
