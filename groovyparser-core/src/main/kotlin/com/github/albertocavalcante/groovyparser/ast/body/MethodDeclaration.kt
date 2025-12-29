package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a method declaration.
 */
class MethodDeclaration(val name: String, val returnType: String) : Node() {

    /** Method parameters */
    val parameters: MutableList<Parameter> = mutableListOf()

    /** Whether this method is static */
    var isStatic: Boolean = false

    /** Whether this method is abstract */
    var isAbstract: Boolean = false

    /** Whether this method is final */
    var isFinal: Boolean = false

    /**
     * Adds a parameter to this method.
     */
    fun addParameter(parameter: Parameter) {
        parameters.add(parameter)
        setAsParentNodeOf(parameter)
    }

    override fun getChildNodes(): List<Node> = parameters.toList()

    override fun toString(): String {
        val staticStr = if (isStatic) "static " else ""
        val paramsStr = parameters.joinToString(", ") { it.toString() }
        return "$staticStr$returnType $name($paramsStr)"
    }
}
