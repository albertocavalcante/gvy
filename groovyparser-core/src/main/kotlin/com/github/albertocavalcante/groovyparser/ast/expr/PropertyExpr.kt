package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a property access expression: `obj.property`
 */
class PropertyExpr(val objectExpression: Expression, val propertyName: String) : Expression() {

    init {
        setAsParentNodeOf(objectExpression)
    }

    override fun getChildNodes(): List<Node> = listOf(objectExpression)

    override fun toString(): String = "PropertyExpr[.$propertyName]"
}
