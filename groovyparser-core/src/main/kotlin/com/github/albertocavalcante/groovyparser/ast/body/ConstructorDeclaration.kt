package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a constructor declaration.
 */
class ConstructorDeclaration(val name: String) : Node() {

    /** Constructor parameters */
    val parameters: MutableList<Parameter> = mutableListOf()

    /**
     * Adds a parameter to this constructor.
     */
    fun addParameter(parameter: Parameter) {
        parameters.add(parameter)
        setAsParentNodeOf(parameter)
    }

    override fun getChildNodes(): List<Node> = parameters.toList()

    override fun toString(): String {
        val paramsStr = parameters.joinToString(", ") { it.toString() }
        return "$name($paramsStr)"
    }
}
