package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents a while loop: `while (condition) { body }`
 */
class WhileStatement(val condition: Expression, val body: Statement) : Statement() {

    init {
        setAsParentNodeOf(condition)
        setAsParentNodeOf(body)
    }

    override fun getChildNodes(): List<Node> = listOf(condition, body)

    override fun toString(): String = "WhileStatement[while (...) {...}]"
}
