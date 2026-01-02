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
        return "$scheme $manager ${classNode.packageName ?: "."} $version $className#$methodName($params)."
    }

    fun local(id: Int): String = "local $id"
}
