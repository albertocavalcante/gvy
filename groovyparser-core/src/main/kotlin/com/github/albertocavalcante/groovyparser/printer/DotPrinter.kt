package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr

/**
 * Prints AST nodes as DOT graph language.
 *
 * Can be rendered using Graphviz to visualize the AST structure.
 *
 * Example usage:
 * ```kotlin
 * val cu = StaticGroovyParser.parse("class Foo { def bar() {} }")
 * val dot = DotPrinter().print(cu)
 * println(dot)
 * // Render with: dot -Tpng -o ast.png
 * ```
 */
class DotPrinter {

    private val output = StringBuilder()
    private var nodeCounter = 0
    private val nodeIds = mutableMapOf<Node, String>()

    /**
     * Graph name.
     */
    var graphName: String = "AST"

    /**
     * Whether to use record-style nodes (more compact).
     */
    var useRecordNodes: Boolean = true

    /**
     * Prints a node and its children as DOT graph.
     */
    fun print(node: Node): String {
        output.clear()
        nodeCounter = 0
        nodeIds.clear()

        output.append("digraph $graphName {\n")
        output.append("  rankdir=TB;\n")
        output.append("  node [fontname=\"Helvetica\", fontsize=10];\n")
        if (useRecordNodes) {
            output.append("  node [shape=record];\n")
        } else {
            output.append("  node [shape=box, style=rounded];\n")
        }
        output.append("\n")

        printNode(node)

        output.append("}\n")
        return output.toString()
    }

    private fun printNode(node: Node): String {
        val nodeId = getOrCreateNodeId(node)
        val label = getNodeLabel(node)

        output.append("  $nodeId [label=$label];\n")

        node.getChildNodes().forEach { child ->
            val childId = printNode(child)
            output.append("  $nodeId -> $childId;\n")
        }

        return nodeId
    }

    private fun getOrCreateNodeId(node: Node): String = nodeIds.getOrPut(node) {
        "n${nodeCounter++}"
    }

    private fun getNodeLabel(node: Node): String = if (useRecordNodes) {
        getRecordLabel(node)
    } else {
        getSimpleLabel(node)
    }

    private fun getRecordLabel(node: Node): String {
        val type = node::class.simpleName ?: "Node"
        val details = getNodeDetails(node)

        return if (details.isEmpty()) {
            "\"$type\""
        } else {
            "\"{$type|${details.joinToString("\\n") { escapeLabel(it) }}}\""
        }
    }

    private fun getSimpleLabel(node: Node): String {
        val type = node::class.simpleName ?: "Node"
        val details = getNodeDetails(node)

        return if (details.isEmpty()) {
            "\"$type\""
        } else {
            "\"$type\\n${details.joinToString("\\n") { escapeLabel(it) }}\""
        }
    }

    private fun getNodeDetails(node: Node): List<String> = when (node) {
        is ClassDeclaration -> listOf("name: ${node.name}")
        is MethodDeclaration -> listOf("name: ${node.name}", "return: ${node.returnType}")
        is FieldDeclaration -> listOf("name: ${node.name}", "type: ${node.type}")
        is Parameter -> listOf("name: ${node.name}", "type: ${node.type}")
        is MethodCallExpr -> listOf("method: ${node.methodName}")
        is VariableExpr -> listOf("var: ${node.name}")
        is ConstantExpr -> listOf("value: ${node.value}")
        is BinaryExpr -> listOf("op: ${node.operator}")
        else -> emptyList()
    }

    private fun escapeLabel(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("|", "\\|")
        .replace("<", "\\<")
        .replace(">", "\\>")
        .replace("{", "\\{")
        .replace("}", "\\}")
}
