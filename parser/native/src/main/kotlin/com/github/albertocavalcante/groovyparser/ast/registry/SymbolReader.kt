package com.github.albertocavalcante.groovyparser.ast.registry

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import java.net.URI

/**
 * Reader component for symbol registry operations.
 * Handles all read/find operations from the symbol storage.
 */
class SymbolReader(private val storage: SymbolStorage) {

    fun findVariable(uri: URI, name: String): Variable? = storage.variableDeclarations[uri]?.get(name)

    fun findMethods(uri: URI, name: String): List<MethodNode> =
        storage.methodDeclarations[uri]?.get(name) ?: emptyList()

    fun findClass(uri: URI, name: String): ClassNode? = storage.classDeclarations[uri]?.get(name)

    fun findImport(uri: URI, name: String): ImportNode? = storage.importDeclarations[uri]?.get(name)

    fun findField(classNode: ClassNode, name: String): ASTNode? = storage.fieldDeclarations[classNode]?.get(name)

    fun getAllVariables(uri: URI): Map<String, Variable> = storage.variableDeclarations[uri] ?: emptyMap()

    fun getAllMethods(uri: URI): Map<String, List<MethodNode>> = storage.methodDeclarations[uri] ?: emptyMap()

    fun getAllClasses(uri: URI): Map<String, ClassNode> = storage.classDeclarations[uri] ?: emptyMap()

    fun getAllImports(uri: URI): Map<String, ImportNode> = storage.importDeclarations[uri] ?: emptyMap()
}
