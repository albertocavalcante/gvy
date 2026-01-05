package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.registry.SymbolReader
import com.github.albertocavalcante.groovyparser.ast.registry.SymbolStorage
import com.github.albertocavalcante.groovyparser.ast.registry.SymbolWriter
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import java.net.URI

/**
 * Registry for storing and retrieving symbol declarations.
 * Refactored to use composition with reader/writer pattern for better maintainability.
 *
 * TODO: This class has 17 functions, which exceeds the original detekt threshold of 13.
 * We've increased the class threshold to 15 for registry/service classes with CRUD operations.
 * Future considerations:
 * - Further split into more granular registries (VariableRegistry, MethodRegistry, etc.)
 * - Use generic registry pattern to reduce boilerplate
 * - Consider if all delegation methods are necessary or if direct access to reader/writer is preferred
 */
class SymbolRegistry {

    private val storage = SymbolStorage()
    val writer = SymbolWriter(storage)
    val reader = SymbolReader(storage)

    // Simplified API (6 functions)
    fun addVariableDeclaration(uri: URI, variable: Variable) = writer.addVariable(uri, variable)
    fun addMethodDeclaration(uri: URI, method: MethodNode) = writer.addMethod(uri, method)
    fun addClassDeclaration(uri: URI, classNode: ClassNode) = writer.addClass(uri, classNode)
    fun addImportDeclaration(uri: URI, importNode: ImportNode) = writer.addImport(uri, importNode)
    fun addFieldDeclaration(classNode: ClassNode, name: String, node: ASTNode) = writer.addField(classNode, name, node)

    fun findVariableDeclaration(uri: URI, name: String): Variable? = reader.findVariable(uri, name)
    fun findMethodDeclarations(uri: URI, name: String): List<MethodNode> = reader.findMethods(uri, name)
    fun findClassDeclaration(uri: URI, name: String): ClassNode? = reader.findClass(uri, name)
    fun findImportDeclaration(uri: URI, name: String): ImportNode? = reader.findImport(uri, name)
    fun findFieldDeclaration(classNode: ClassNode, name: String): ASTNode? = reader.findField(classNode, name)

    fun getVariableDeclarations(uri: URI): Map<String, Variable> = reader.getAllVariables(uri)
    fun getMethodDeclarations(uri: URI): Map<String, List<MethodNode>> = reader.getAllMethods(uri)
    fun getClassDeclarations(uri: URI): Map<String, ClassNode> = reader.getAllClasses(uri)
    fun getImportDeclarations(uri: URI): Map<String, ImportNode> = reader.getAllImports(uri)

    fun clear() = storage.clear()
    fun isEmpty(): Boolean = storage.isEmpty()
    fun getStatistics(): Map<String, Int> = storage.getStatistics()
}
