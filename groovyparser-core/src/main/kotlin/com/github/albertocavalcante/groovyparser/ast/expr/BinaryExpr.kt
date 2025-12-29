package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a binary expression: `left op right`
 */
class BinaryExpr(val left: Expression, val operator: String, val right: Expression) : Expression() {

    init {
        setAsParentNodeOf(left)
        setAsParentNodeOf(right)
    }

    override fun getChildNodes(): List<Node> = listOf(left, right)

    override fun toString(): String = "BinaryExpr[$operator]"
}
