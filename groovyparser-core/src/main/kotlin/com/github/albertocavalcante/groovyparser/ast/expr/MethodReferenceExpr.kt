package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Java 8 method reference expression: `Class::method`
 *
 * Example:
 * ```groovy
 * def toUpper = String::toUpperCase
 * def printer = System.out::println
 * list.stream().map(String::valueOf)
 * ```
 */
class MethodReferenceExpr(val objectExpression: Expression, val methodName: Expression) : Expression() {

    init {
        setAsParentNodeOf(objectExpression)
        setAsParentNodeOf(methodName)
    }

    override fun getChildNodes(): List<Node> = listOf(objectExpression, methodName)

    override fun toString(): String = "MethodReferenceExpr"
}
