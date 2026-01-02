package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a GString (interpolated string) expression: `"Hello, $name!"`
 */
class GStringExpr : Expression() {

    /** The string parts (literal text between interpolations) */
    private val stringParts: MutableList<String> = mutableListOf()

    /** The embedded expressions */
    private val values: MutableList<Expression> = mutableListOf()

    /** Returns the string parts */
    val strings: List<String>
        get() = stringParts.toList()

    /** Returns the embedded expressions */
    val expressions: List<Expression>
        get() = values.toList()

    /**
     * Adds a string part.
     */
    fun addString(str: String) {
        stringParts.add(str)
    }

    /**
     * Adds an embedded expression.
     */
    fun addExpression(expr: Expression) {
        values.add(expr)
        setAsParentNodeOf(expr)
    }

    override fun getChildNodes(): List<Node> = values.toList()

    override fun toString(): String = "GStringExpr[\"...\"]"
}
