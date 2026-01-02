package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Variable declaration expression: `def x = value` or `Type x = value`
 *
 * Example:
 * ```groovy
 * def name = "John"
 * String greeting = "Hello"
 * def (a, b) = [1, 2]  // Multiple assignment
 * ```
 */
class DeclarationExpr(val variableExpression: Expression, val rightExpression: Expression, val type: String = "def") :
    Expression() {

    init {
        setAsParentNodeOf(variableExpression)
        setAsParentNodeOf(rightExpression)
    }

    override fun getChildNodes(): List<Node> = listOf(variableExpression, rightExpression)

    override fun toString(): String = "DeclarationExpr[$type]"
}
