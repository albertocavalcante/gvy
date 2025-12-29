package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a break statement: `break` or `break label`
 */
class BreakStatement(val label: String? = null) : Statement() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = if (label != null) "BreakStatement[$label]" else "BreakStatement"
}
