package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents an assert statement:
 * ```groovy
 * assert condition
 * assert condition : "message"
 * ```
 */
class AssertStatement(val condition: Expression, val message: Expression? = null) : Statement() {

    init {
        setAsParentNodeOf(condition)
        message?.let { setAsParentNodeOf(it) }
    }

    override fun getChildNodes(): List<Node> = listOfNotNull(condition, message)

    override fun toString(): String = if (message != null) {
        "AssertStatement[with message]"
    } else {
        "AssertStatement"
    }
}
