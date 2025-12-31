package com.github.albertocavalcante.groovyparser.utils

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr

/**
 * Utility functions for navigating and querying the AST.
 *
 * Similar to JavaParser's Navigator class.
 */
object NodeUtils {

    /**
     * Finds all nodes of a specific type in the AST.
     */
    inline fun <reified T : Node> findAll(root: Node): List<T> {
        val results = mutableListOf<T>()
        collectNodes(root, T::class.java, results)
        return results
    }

    /**
     * Finds the first node of a specific type in the AST.
     */
    inline fun <reified T : Node> findFirst(root: Node): T? = findAll<T>(root).firstOrNull()

    /**
     * Collects all nodes of a specific type.
     */
    fun <T : Node> collectNodes(node: Node, type: Class<T>, results: MutableList<T>) {
        if (type.isInstance(node)) {
            @Suppress("UNCHECKED_CAST")
            results.add(node as T)
        }
        node.getChildNodes().forEach { child ->
            collectNodes(child, type, results)
        }
    }

    /**
     * Finds a class by name in the compilation unit.
     */
    fun findClass(cu: CompilationUnit, name: String): ClassDeclaration? =
        cu.types.filterIsInstance<ClassDeclaration>().find { it.name == name }

    /**
     * Finds a method by name in a class.
     */
    fun findMethod(classDecl: ClassDeclaration, name: String): MethodDeclaration? =
        classDecl.methods.find { it.name == name }

    /**
     * Finds all methods with a given name in a class (for overloaded methods).
     */
    fun findMethods(classDecl: ClassDeclaration, name: String): List<MethodDeclaration> =
        classDecl.methods.filter { it.name == name }

    /**
     * Finds a field by name in a class.
     */
    fun findField(classDecl: ClassDeclaration, name: String): FieldDeclaration? =
        classDecl.fields.find { it.name == name }

    /**
     * Finds all method calls to a specific method name.
     */
    fun findMethodCalls(root: Node, methodName: String): List<MethodCallExpr> =
        findAll<MethodCallExpr>(root).filter { it.methodName == methodName }

    /**
     * Finds all variable references with a specific name.
     */
    fun findVariableReferences(root: Node, variableName: String): List<VariableExpr> =
        findAll<VariableExpr>(root).filter { it.name == variableName }

    /**
     * Gets all ancestor nodes from this node up to the root.
     */
    fun getAncestors(node: Node): List<Node> = buildList {
        var current = node.parentNode
        while (current != null) {
            add(current)
            current = current.parentNode
        }
    }

    /**
     * Gets the nearest ancestor of a specific type.
     */
    inline fun <reified T : Node> findAncestor(node: Node): T? = getAncestors(node).filterIsInstance<T>().firstOrNull()

    /**
     * Gets the containing class for a node.
     */
    fun getContainingClass(node: Node): ClassDeclaration? = findAncestor(node)

    /**
     * Gets the containing method for a node.
     */
    fun getContainingMethod(node: Node): MethodDeclaration? = findAncestor(node)

    /**
     * Gets the containing compilation unit for a node.
     */
    fun getCompilationUnit(node: Node): CompilationUnit? = findAncestor(node)

    /**
     * Returns all leaf nodes (nodes with no children) in the AST.
     */
    fun getLeafNodes(root: Node): List<Node> = buildList {
        fun collect(node: Node) {
            val children = node.getChildNodes()
            if (children.isEmpty()) {
                add(node)
            } else {
                children.forEach { collect(it) }
            }
        }
        collect(root)
    }

    /**
     * Counts the total number of nodes in the AST.
     */
    fun countNodes(root: Node): Int {
        var count = 1
        root.getChildNodes().forEach { count += countNodes(it) }
        return count
    }

    /**
     * Returns the depth of the AST from this node.
     */
    fun getDepth(root: Node): Int {
        val childDepths = root.getChildNodes().map { getDepth(it) }
        return if (childDepths.isEmpty()) 1 else 1 + childDepths.max()
    }

    /**
     * Returns the depth of a node from the root.
     */
    fun getNodeDepth(node: Node): Int = getAncestors(node).size

    /**
     * Walks the AST in pre-order (parent before children).
     */
    fun walkPreOrder(root: Node, action: (Node) -> Unit) {
        action(root)
        root.getChildNodes().forEach { walkPreOrder(it, action) }
    }

    /**
     * Walks the AST in post-order (children before parent).
     */
    fun walkPostOrder(root: Node, action: (Node) -> Unit) {
        root.getChildNodes().forEach { walkPostOrder(it, action) }
        action(root)
    }

    /**
     * Walks the AST in breadth-first order.
     */
    fun walkBreadthFirst(root: Node, action: (Node) -> Unit) {
        val queue = ArrayDeque<Node>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            action(node)
            queue.addAll(node.getChildNodes())
        }
    }
}

/**
 * Extension function to find all nodes of a type.
 */
inline fun <reified T : Node> Node.findAll(): List<T> = NodeUtils.findAll(this)

/**
 * Extension function to find the first node of a type.
 */
inline fun <reified T : Node> Node.findFirst(): T? = NodeUtils.findFirst(this)

/**
 * Extension function to get ancestors.
 */
fun Node.ancestors(): List<Node> = NodeUtils.getAncestors(this)

/**
 * Extension function to find ancestor of type.
 */
inline fun <reified T : Node> Node.findAncestor(): T? = NodeUtils.findAncestor(this)

/**
 * Extension function for pre-order walk.
 */
fun Node.walkPreOrder(action: (Node) -> Unit) = NodeUtils.walkPreOrder(this, action)

/**
 * Extension function for post-order walk.
 */
fun Node.walkPostOrder(action: (Node) -> Unit) = NodeUtils.walkPostOrder(this, action)
