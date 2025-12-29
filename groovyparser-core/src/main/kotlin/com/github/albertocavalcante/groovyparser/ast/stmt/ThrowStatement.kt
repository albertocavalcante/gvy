package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents a throw statement: `throw new Exception("message")`
 */
class ThrowStatement(val expression: Expression) : Statement() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = "ThrowStatement"
}
