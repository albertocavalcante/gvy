package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a method call expression: `obj.method(args)` or `method(args)`
 */
class MethodCallExpr(val objectExpression: Expression?, val methodName: String) : Expression() {

    /** Arguments to the method call */
    private val args: MutableList<Expression> = mutableListOf()

    /** Returns the arguments */
    val arguments: List<Expression>
        get() = args.toList()

    init {
        setAsParentNodeOf(objectExpression)
    }

    /**
     * Adds an argument to this method call.
     */
    fun addArgument(argument: Expression) {
        args.add(argument)
        setAsParentNodeOf(argument)
    }

    override fun getChildNodes(): List<Node> {
        val children = mutableListOf<Node>()
        objectExpression?.let { children.add(it) }
        children.addAll(args)
        return children
    }

    override fun toString(): String = "MethodCallExpr[$methodName(...)]"
}
