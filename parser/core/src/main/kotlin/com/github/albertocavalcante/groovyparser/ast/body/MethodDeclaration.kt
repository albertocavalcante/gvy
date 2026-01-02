package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement

/**
 * Represents a method declaration.
 */
class MethodDeclaration(val name: String, val returnType: String) : Node() {

    /** Method parameters */
    val parameters: MutableList<Parameter> = mutableListOf()

    /** The method body (null for abstract methods) */
    var body: Statement? = null
        internal set(value) {
            field = value
            setAsParentNodeOf(value)
        }

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

    override fun getChildNodes(): List<Node> {
        val children = mutableListOf<Node>()
        children.addAll(parameters)
        body?.let { children.add(it) }
        return children
    }

    override fun toString(): String {
        val staticStr = if (isStatic) "static " else ""
        val paramsStr = parameters.joinToString(", ") { it.toString() }
        return "$staticStr$returnType $name($paramsStr)"
    }
}
