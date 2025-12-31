package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a continue statement: `continue` or `continue label`
 */
class ContinueStatement(val label: String? = null) : Statement() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = if (label != null) "ContinueStatement[$label]" else "ContinueStatement"
}
