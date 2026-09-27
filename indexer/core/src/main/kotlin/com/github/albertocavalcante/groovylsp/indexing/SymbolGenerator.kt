package com.github.albertocavalcante.groovylsp.indexing

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode

class SymbolGenerator(
    private val scheme: String = "scip-groovy",
    // Default, but should be passed dynamically
    private val manager: String = "maven",
) {
    fun forClass(classNode: ClassNode, version: String = "0.0.0"): String =
        "$scheme $manager ${classNode.packageName ?: "."} $version ${classNode.name}#"

    fun forMethod(classNode: ClassNode, methodNode: MethodNode, version: String = "0.0.0"): String {
        val className = classNode.name
        val methodName = methodNode.name
        // TODO: This uses simple names if types are not resolved (CONVERSION phase).
        // For full accuracy, we need SEMANTIC_ANALYSIS to get FQNs.
        val params = methodNode.parameters.joinToString(",") { it.type.name }
        val descriptor = "${escapeDescriptor(methodName)}(${escapeDescriptor(params)})."
        return "$scheme $manager ${classNode.packageName ?: "."} $version $className#$descriptor"
    }

    fun local(id: Int): String = "local $id"

    private fun escapeDescriptor(value: String): String = if (value.all(::isSimpleIdentifierChar)) {
        value
    } else {
        "`${value.replace("`", "``")}`"
    }

    private fun isSimpleIdentifierChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '+' || ch == '-' || ch == '$'
}
