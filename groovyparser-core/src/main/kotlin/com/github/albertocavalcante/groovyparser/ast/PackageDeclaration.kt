package com.github.albertocavalcante.groovyparser.ast

/**
 * Represents a package declaration: `package com.example`
 */
class PackageDeclaration(val name: String) : Node() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String = "package $name"
}
