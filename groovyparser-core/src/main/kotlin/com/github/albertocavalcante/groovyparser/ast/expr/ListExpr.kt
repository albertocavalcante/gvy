package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a list literal expression: `[1, 2, 3]`
 */
class ListExpr(elements: List<Expression> = emptyList()) : Expression() {

    /** Elements of the list */
    val elements: MutableList<Expression> = elements.toMutableList()

    init {
        elements.forEach { setAsParentNodeOf(it) }
    }

    fun addElement(element: Expression) {
        elements.add(element)
        setAsParentNodeOf(element)
    }

    override fun getChildNodes(): List<Node> = elements.toList()

    override fun toString(): String = "ListExpr[${elements.size} element(s)]"
}
