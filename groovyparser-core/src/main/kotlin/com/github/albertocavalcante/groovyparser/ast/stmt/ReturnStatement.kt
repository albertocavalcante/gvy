package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents a return statement: `return expr` or `return`
 */
class ReturnStatement(val expression: Expression? = null) : Statement() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOfNotNull(expression)

    override fun toString(): String =
        if (expression != null) "ReturnStatement[return ...]" else "ReturnStatement[return]"
}
