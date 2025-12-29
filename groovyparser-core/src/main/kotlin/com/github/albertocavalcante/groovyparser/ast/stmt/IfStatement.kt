package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.Expression

/**
 * Represents an if statement: `if (condition) { then } else { else }`
 */
class IfStatement(val condition: Expression, val thenStatement: Statement, val elseStatement: Statement? = null) :
    Statement() {

    init {
        setAsParentNodeOf(condition)
        setAsParentNodeOf(thenStatement)
        setAsParentNodeOf(elseStatement)
    }

    override fun getChildNodes(): List<Node> {
        val children = mutableListOf<Node>(condition, thenStatement)
        elseStatement?.let { children.add(it) }
        return children
    }

    override fun toString(): String {
        val elseStr = if (elseStatement != null) " else {...}" else ""
        return "IfStatement[if (...) {...}$elseStr]"
    }
}
