package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a method or constructor parameter.
 */
class Parameter(val name: String, val type: String) : Node() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = "$type $name"
}
