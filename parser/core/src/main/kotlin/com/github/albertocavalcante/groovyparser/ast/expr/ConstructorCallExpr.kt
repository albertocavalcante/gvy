package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a constructor call expression: `new ClassName(args)`
 */
class ConstructorCallExpr(val typeName: String) : Expression() {

    /** Arguments to the constructor */
    val arguments: MutableList<Expression> = mutableListOf()

    fun addArgument(argument: Expression) {
        arguments.add(argument)
        setAsParentNodeOf(argument)
    }

    override fun getChildNodes(): List<Node> = arguments.toList()

    override fun toString(): String = "ConstructorCallExpr[new $typeName()]"
}
