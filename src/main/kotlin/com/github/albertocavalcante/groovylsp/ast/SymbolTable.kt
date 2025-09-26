package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.VariableExpression
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Symbol table for caching symbol definitions and resolutions.
 * Provides fast lookup for go-to-definition functionality.
 */
class SymbolTable {

    // Variable name to declaration mapping per URI
    private val variableDeclarations = ConcurrentHashMap<URI, MutableMap<String, Variable>>()

    // Method name to method node mapping per URI
    private val methodDeclarations = ConcurrentHashMap<URI, MutableMap<String, MutableList<MethodNode>>>()

    // Class name to class node mapping per URI
    private val classDeclarations = ConcurrentHashMap<URI, MutableMap<String, ClassNode>>()

    // Import name to import node mapping per URI
    private val importDeclarations = ConcurrentHashMap<URI, MutableMap<String, ImportNode>>()

    // Field/property name to node mapping per class
    private val fieldDeclarations = ConcurrentHashMap<ClassNode, MutableMap<String, ASTNode>>()

    /**
     * Add a variable declaration to the symbol table
     */
    fun addVariableDeclaration(uri: URI, variable: Variable) {
        variableDeclarations.computeIfAbsent(uri) { ConcurrentHashMap() }[variable.name] = variable
    }

    /**
     * Add a method declaration to the symbol table
     */
    fun addMethodDeclaration(uri: URI, method: MethodNode) {
        val methods = methodDeclarations.computeIfAbsent(uri) { ConcurrentHashMap() }
        methods.computeIfAbsent(method.name) { mutableListOf() }.add(method)
    }

    /**
     * Add a class declaration to the symbol table
     */
    fun addClassDeclaration(uri: URI, classNode: ClassNode) {
        classDeclarations.computeIfAbsent(uri) { ConcurrentHashMap() }[classNode.nameWithoutPackage] = classNode
    }

    /**
     * Add an import declaration to the symbol table
     */
    fun addImportDeclaration(uri: URI, importNode: ImportNode) {
        val importName = importNode.alias ?: importNode.className?.substringAfterLast('.') ?: return
        importDeclarations.computeIfAbsent(uri) { ConcurrentHashMap() }[importName] = importNode
    }

    /**
     * Add a field or property declaration to the symbol table
     */
    fun addFieldDeclaration(classNode: ClassNode, name: String, node: ASTNode) {
        fieldDeclarations.computeIfAbsent(classNode) { ConcurrentHashMap() }[name] = node
    }

    /**
     * Find a variable declaration by name in the given URI
     */
    fun findVariableDeclaration(uri: URI, name: String): Variable? = variableDeclarations[uri]?.get(name)

    /**
     * Find method declarations by name in the given URI
     */
    fun findMethodDeclarations(uri: URI, name: String): List<MethodNode> =
        methodDeclarations[uri]?.get(name) ?: emptyList()

    /**
     * Find a class declaration by name in the given URI
     */
    fun findClassDeclaration(uri: URI, name: String): ClassNode? = classDeclarations[uri]?.get(name)

    /**
     * Find an import declaration by name in the given URI
     */
    fun findImportDeclaration(uri: URI, name: String): ImportNode? = importDeclarations[uri]?.get(name)

    /**
     * Find a field or property declaration in a class
     */
    fun findFieldDeclaration(classNode: ClassNode, name: String): ASTNode? = fieldDeclarations[classNode]?.get(name)

    /**
     * Build the symbol table from an AstVisitor
     */
    fun buildFromVisitor(visitor: AstVisitor) {
        clear()

        visitor.getAllNodes().forEach { node ->
            val uri = visitor.getUri(node) ?: return@forEach

            when (node) {
                is Variable -> addVariableDeclaration(uri, node)
                is Parameter -> addVariableDeclaration(uri, node)
                is MethodNode -> addMethodDeclaration(uri, node)
                is ClassNode -> {
                    addClassDeclaration(uri, node)
                    // Add fields and properties for this class
                    node.fields.forEach { field ->
                        addFieldDeclaration(node, field.name, field)
                    }
                    node.properties.forEach { property ->
                        addFieldDeclaration(node, property.name, property)
                    }
                }
                is ImportNode -> addImportDeclaration(uri, node)
                is org.codehaus.groovy.ast.expr.DeclarationExpression -> {
                    // Extract variable from declaration expression
                    if (!node.isMultipleAssignmentDeclaration) {
                        val leftExpr = node.leftExpression
                        if (leftExpr is org.codehaus.groovy.ast.expr.VariableExpression) {
                            // Create a synthetic Variable for the declaration
                            val variable = object : org.codehaus.groovy.ast.Variable {
                                override fun getName() = leftExpr.name
                                override fun getType() = node.variableExpression.type
                                override fun getOriginType() = node.variableExpression.originType
                                override fun isDynamicTyped() = node.variableExpression.isDynamicTyped
                                override fun isClosureSharedVariable() = false
                                override fun setClosureSharedVariable(inClosure: Boolean) {
                                    // TODO: Implement closure shared variable tracking if needed for
                                    //       advanced symbol resolution
                                }
                                override fun getModifiers() = node.variableExpression.modifiers
                                override fun isInStaticContext() = node.variableExpression.isInStaticContext
                                override fun hasInitialExpression() = node.rightExpression != null
                                override fun getInitialExpression() = node.rightExpression
                            }
                            addVariableDeclaration(uri, variable)
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolve a symbol to its definition
     */
    fun resolveSymbol(node: ASTNode, visitor: AstVisitor): Variable? {
        val uri = visitor.getUri(node) ?: return null

        return when (node) {
            is VariableExpression -> {
                // Look for variable declaration
                findVariableDeclaration(uri, node.name)
                    // If not found locally, check if it's a field access
                    ?: findFieldInScope(node, visitor) as? Variable
                    // Check for implicit field access
                    ?: findFieldInEnclosingClass(node, visitor)
            }
            is Parameter -> findVariableDeclaration(uri, node.name)
            else -> null
        }
    }

    /**
     * Find a field in the current class scope
     */
    private fun findFieldInScope(variableExpr: VariableExpression, visitor: AstVisitor): ASTNode? {
        // Find the enclosing class
        var current = visitor.getParent(variableExpr)
        while (current != null && current !is ClassNode) {
            current = visitor.getParent(current)
        }

        return if (current is ClassNode) {
            findFieldDeclaration(current, variableExpr.name)
                // Also check directly on the ClassNode for built-in fields
                ?: current.getField(variableExpr.name)
                ?: current.getProperty(variableExpr.name)
        } else {
            null
        }
    }

    /**
     * Find field in the enclosing class context for implicit field access
     */
    private fun findFieldInEnclosingClass(variableExpr: VariableExpression, visitor: AstVisitor): Variable? {
        // Look for the field in symbol tables of all classes in the current URI
        val uri = visitor.getUri(variableExpr) ?: return null
        val classDecls = classDeclarations[uri] ?: return null

        // For each class, check if it has a field with this name
        for (classNode in classDecls.values) {
            val field = findFieldDeclaration(classNode, variableExpr.name)
            if (field != null) {
                // Convert field to Variable if needed
                return when (field) {
                    is Variable -> field
                    is org.codehaus.groovy.ast.FieldNode -> field
                    is org.codehaus.groovy.ast.PropertyNode -> field
                    else -> null
                }
            }
        }
        return null
    }

    /**
     * Get all variable declarations for a URI
     */
    fun getVariableDeclarations(uri: URI): Map<String, Variable> = variableDeclarations[uri] ?: emptyMap()

    /**
     * Get all method declarations for a URI
     */
    fun getMethodDeclarations(uri: URI): Map<String, List<MethodNode>> = methodDeclarations[uri] ?: emptyMap()

    /**
     * Get all class declarations for a URI
     */
    fun getClassDeclarations(uri: URI): Map<String, ClassNode> = classDeclarations[uri] ?: emptyMap()

    /**
     * Get all import declarations for a URI
     */
    fun getImportDeclarations(uri: URI): Map<String, ImportNode> = importDeclarations[uri] ?: emptyMap()

    /**
     * Clear all symbol tables
     */
    fun clear() {
        variableDeclarations.clear()
        methodDeclarations.clear()
        classDeclarations.clear()
        importDeclarations.clear()
        fieldDeclarations.clear()
    }

    /**
     * Check if the symbol table is empty
     */
    fun isEmpty(): Boolean = variableDeclarations.isEmpty() &&
        methodDeclarations.isEmpty() &&
        classDeclarations.isEmpty() &&
        importDeclarations.isEmpty() &&
        fieldDeclarations.isEmpty()

    /**
     * Get statistics about the symbol table
     */
    fun getStatistics(): Map<String, Int> = mapOf(
        "variables" to variableDeclarations.values.sumOf { it.size },
        "methods" to methodDeclarations.values.sumOf { it.values.sumOf { list -> list.size } },
        "classes" to classDeclarations.values.sumOf { it.size },
        "imports" to importDeclarations.values.sumOf { it.size },
        "fields" to fieldDeclarations.values.sumOf { it.size },
    )
}
