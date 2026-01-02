package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents a for loop: `for (var in collection) { body }` or `for (init; cond; update) { body }`
 */
class ForStatement(val variableName: String, val collectionExpression: Expression, val body: Statement) : Statement() {

    init {
        setAsParentNodeOf(collectionExpression)
        setAsParentNodeOf(body)
    }

    override fun getChildNodes(): List<Node> = listOf(collectionExpression, body)

    override fun toString(): String = "ForStatement[for ($variableName in ...) {...}]"
}
