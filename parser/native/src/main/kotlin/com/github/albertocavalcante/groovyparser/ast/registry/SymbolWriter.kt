package com.github.albertocavalcante.groovyparser.ast.registry

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Writer component for symbol registry operations.
 * Handles all write/add operations to the symbol storage.
 */
class SymbolWriter(private val storage: SymbolStorage) {

    fun addVariable(uri: URI, variable: Variable) {
        storage.variableDeclarations
            .computeIfAbsent(uri) { ConcurrentHashMap() }[variable.name] = variable
    }

    fun addMethod(uri: URI, method: MethodNode) {
        storage.methodDeclarations
            .computeIfAbsent(uri) { ConcurrentHashMap() }
            .computeIfAbsent(method.name) { mutableListOf() }
            .add(method)
    }

    fun addClass(uri: URI, classNode: ClassNode) {
        storage.classDeclarations
            .computeIfAbsent(uri) { ConcurrentHashMap() }[classNode.name] = classNode
    }

    fun addImport(uri: URI, importNode: ImportNode) {
        val key = importNode.alias ?: importNode.className ?: "unknown-import"
        storage.importDeclarations
            .computeIfAbsent(uri) { ConcurrentHashMap() }[key] = importNode
    }

    fun addField(classNode: ClassNode, name: String, node: ASTNode) {
        storage.fieldDeclarations
            .computeIfAbsent(classNode) { ConcurrentHashMap() }[name] = node
    }

    fun clear() = storage.clear()
}
