package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a ternary/conditional expression: `condition ? trueExpr : falseExpr`
 *
 * Also represents the Elvis operator: `value ?: default`
 */
class TernaryExpr(val condition: Expression, val trueExpression: Expression, val falseExpression: Expression) :
    Expression() {

    init {
        setAsParentNodeOf(condition)
        setAsParentNodeOf(trueExpression)
        setAsParentNodeOf(falseExpression)
    }

    /** True if this is an Elvis operator (condition == trueExpression) */
    val isElvis: Boolean
        get() = condition === trueExpression

    override fun getChildNodes(): List<Node> = listOf(condition, trueExpression, falseExpression)

    override fun toString(): String = if (isElvis) "ElvisExpr" else "TernaryExpr"
}
