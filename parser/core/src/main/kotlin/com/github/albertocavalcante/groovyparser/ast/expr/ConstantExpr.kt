package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a constant/literal expression: `42`, `"hello"`, `true`, etc.
 */
class ConstantExpr(val value: Any?) : Expression() {

    /** The type of the constant */
    val type: String
        get() = when (value) {
            null -> "null"
            is String -> "String"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Double -> "double"
            is Boolean -> "boolean"
            is Char -> "char"
            else -> value::class.simpleName ?: "Object"
        }

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = "ConstantExpr[$value]"
}
