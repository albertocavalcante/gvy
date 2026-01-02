package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Array creation expression: `new Type[size]` or `new Type[] {elements}`
 *
 * Example:
 * ```groovy
 * def arr = new int[5]
 * def arr2 = new String[] {"a", "b", "c"}
 * def arr3 = [1, 2, 3] as int[]
 * ```
 */
class ArrayExpr(
    val elementType: String,
    val sizeExpressions: List<Expression> = emptyList(),
    val initExpressions: List<Expression> = emptyList(),
) : Expression() {

    init {
        sizeExpressions.forEach { setAsParentNodeOf(it) }
        initExpressions.forEach { setAsParentNodeOf(it) }
    }

    override fun getChildNodes(): List<Node> = sizeExpressions + initExpressions

    override fun toString(): String = "ArrayExpr[$elementType]"
}
