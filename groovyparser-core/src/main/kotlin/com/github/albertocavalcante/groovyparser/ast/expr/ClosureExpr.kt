package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement

/**
 * Represents a closure expression: `{ params -> body }` or `{ body }`
 */
class ClosureExpr : Expression() {

    /** Closure parameters */
    private val params: MutableList<Parameter> = mutableListOf()

    /** The body of the closure */
    var body: Statement? = null
        internal set(value) {
            field = value
            setAsParentNodeOf(value)
        }

    /** Returns the parameters */
    val parameters: List<Parameter>
        get() = params.toList()

    /**
     * Adds a parameter to this closure.
     */
    fun addParameter(parameter: Parameter) {
        params.add(parameter)
        setAsParentNodeOf(parameter)
    }

    override fun getChildNodes(): List<Node> {
        val children = mutableListOf<Node>()
        children.addAll(params)
        body?.let { children.add(it) }
        return children
    }

    override fun toString(): String = "ClosureExpr[{...}]"
}
