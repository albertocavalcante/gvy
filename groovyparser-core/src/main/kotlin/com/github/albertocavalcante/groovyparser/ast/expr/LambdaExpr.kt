package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement

/**
 * Java 8 lambda expression: `(params) -> body`
 *
 * Example:
 * ```groovy
 * def add = (a, b) -> a + b
 * list.stream().filter(x -> x > 0)
 * ```
 */
class LambdaExpr : Expression() {
    val parameters: MutableList<Parameter> = mutableListOf()
    var body: Statement? = null

    fun addParameter(param: Parameter) {
        setAsParentNodeOf(param)
        parameters.add(param)
    }

    override fun getChildNodes(): List<Node> = buildList {
        addAll(parameters)
        body?.let { add(it) }
    }

    override fun toString(): String = "LambdaExpr(${parameters.size} params)"
}
