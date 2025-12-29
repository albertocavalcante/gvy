package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a variable reference: `variableName`
 */
class VariableExpr(val name: String) : Expression() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = "VariableExpr[$name]"
}
