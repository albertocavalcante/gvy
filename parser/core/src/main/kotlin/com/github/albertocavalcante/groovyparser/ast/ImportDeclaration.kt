package com.github.albertocavalcante.groovyparser.ast

/**
 * Represents an import declaration: `import java.util.List`
 */
class ImportDeclaration(val name: String, val isStatic: Boolean = false, val isStarImport: Boolean = false) : Node() {

    override fun getChildNodes(): List<Node> = emptyList()

    override fun toString(): String {
        val staticStr = if (isStatic) "static " else ""
        val starStr = if (isStarImport) ".*" else ""
        return "import $staticStr$name$starStr"
    }
}
