package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a cast expression: `(Type) expr` or `expr as Type`
 */
class CastExpr(val expression: Expression, val targetType: String, val isCoercion: Boolean = false) : Expression() {

    init {
        setAsParentNodeOf(expression)
    }

    override fun getChildNodes(): List<Node> = listOf(expression)

    override fun toString(): String = if (isCoercion) {
        "CastExpr[as $targetType]"
    } else {
        "CastExpr[($targetType)]"
    }
}
