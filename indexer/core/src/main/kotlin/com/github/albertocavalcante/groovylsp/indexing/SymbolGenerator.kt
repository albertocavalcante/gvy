package com.github.albertocavalcante.groovylsp.indexing

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter

object SymbolGenerator {
    private const val SCHEME = "scip-java" // Reusing java scheme for consistency
    private const val MANAGER = "maven"

    fun forClass(classNode: ClassNode): String =
        "$SCHEME $MANAGER ${classNode.packageName ?: "."} 0.0.0 ${classNode.name}#"

    fun forMethod(classNode: ClassNode, methodNode: MethodNode): String {
        val className = classNode.name
        val methodName = methodNode.name
        val params = methodNode.parameters.joinToString(",") { it.type.name }
        // Simple signature for now: method(paramType,paramType)
        // Disambiguation is tricky without full type resolution, this is best effort
        return "$SCHEME $MANAGER ${classNode.packageName ?: "."} 0.0.0 $className#$methodName($params)."
    }

    fun local(id: Int): String = "local $id"
}
