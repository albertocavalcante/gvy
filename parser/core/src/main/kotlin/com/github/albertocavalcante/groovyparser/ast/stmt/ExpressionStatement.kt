package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents an expression used as a statement: `expr;`
 */
class ExpressionStatement(val expression: Expression) : Statement() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "ExpressionStatement[$expression]"
}
