package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a range expression: `1..10` or `1..<10`
 */
class RangeExpr(val from: Expression, val to: Expression, val inclusive: Boolean = true) : Expression() {

    init {
        setAsParentNodeOf(from)
        setAsParentNodeOf(to)
    }

    override fun getChildNodes(): List<Node> = listOf(from, to)

    override fun toString(): String = if (inclusive) "RangeExpr[..]" else "RangeExpr[..<]"
}
