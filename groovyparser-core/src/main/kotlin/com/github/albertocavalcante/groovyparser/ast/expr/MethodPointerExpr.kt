package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Method pointer expression: `obj.&method`
 * Gets a reference to a method as a Closure.
 *
 * Example:
 * ```groovy
 * def printer = System.out.&println
 * printer("Hello")  // Calls System.out.println("Hello")
 *
 * def list = [1, 2, 3]
 * list.each(this.&processItem)
 * ```
 */
class MethodPointerExpr(val objectExpression: Expression, val methodName: Expression) : Expression() {

    init {
        setAsParentNodeOf(objectExpression)
        setAsParentNodeOf(methodName)
    }

    override fun getChildNodes(): List<Node> = listOf(objectExpression, methodName)

    override fun toString(): String = "MethodPointerExpr"
}
