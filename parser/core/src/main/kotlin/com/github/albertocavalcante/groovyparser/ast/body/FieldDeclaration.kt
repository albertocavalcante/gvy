package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a field declaration.
 */
class FieldDeclaration(val name: String, val type: String) : Node() {

    /** Whether this field is static */
    var isStatic: Boolean = false

    /** Whether this field is final */
    var isFinal: Boolean = false

    /** Whether this field has an initializer */
    var hasInitializer: Boolean = false

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String {
        val staticStr = if (isStatic) "static " else ""
        val finalStr = if (isFinal) "final " else ""
        return "$staticStr$finalStr$type $name"
    }
}
