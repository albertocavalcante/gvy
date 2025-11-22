package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks parent-child relationships between AST nodes and their URI mappings.
 * Extracted from AstVisitor to reduce function count and provide focused responsibility.
 */
class NodeRelationshipTracker {

    // Parent tracking using a stack - following fork-groovy pattern
    private val nodeStack = ArrayDeque<ASTNode>()

    // Node storage per URI for cross-file support - using thread-safe collections
    private val nodesByUri = ConcurrentHashMap<URI, MutableCollection<ASTNode>>()
    private val classNodesByUri = ConcurrentHashMap<URI, MutableCollection<ClassNode>>()

    // Parent-child relationships
    private val parentMap = ConcurrentHashMap<ASTNode, ASTNode>()

    // URI mapping for nodes
    private val nodeUriMap = ConcurrentHashMap<ASTNode, URI>()

    /**
     * Push a node onto the tracking stack and establish relationships.
     */
    fun pushNode(node: ASTNode, currentUri: URI?) {
        // Track parent-child relationship
        if (nodeStack.isNotEmpty()) {
            parentMap[node] = nodeStack.last()
        }

        // Add to stack
        nodeStack.addLast(node)

        // Store by URI if available
        currentUri?.let { uri ->
            nodeUriMap[node] = uri

            // Add to nodes collection
            nodesByUri.computeIfAbsent(uri) {
                ConcurrentHashMap.newKeySet()
            }.add(node)

            // Track class nodes separately for quick access
            if (node is ClassNode) {
                classNodesByUri.computeIfAbsent(uri) {
                    ConcurrentHashMap.newKeySet()
                }.add(node)
            }
        }
    }

    /**
     * Pop the top node from the tracking stack.
     */
    fun popNode() {
        if (nodeStack.isNotEmpty()) {
            nodeStack.removeLast()
        }
    }

    /**
     * Get the parent of the specified node.
     */
    fun getParent(node: ASTNode): ASTNode? = parentMap[node]

    /**
     * Get the URI associated with the specified node.
     */
    fun getUri(node: ASTNode): URI? = nodeUriMap[node]

    /**
     * Get all nodes for a specific URI.
     */
    fun getNodes(uri: URI): List<ASTNode> = nodesByUri[uri]?.toList() ?: emptyList()

    /**
     * Get all nodes from all URIs.
     */
    fun getAllNodes(): List<ASTNode> = nodesByUri.values.flatten()

    /**
     * Get all class nodes from all URIs.
     */
    fun getAllClassNodes(): List<ClassNode> = classNodesByUri.values.flatten()

    /**
     * Check if ancestor contains descendant in the node tree.
     */
    fun contains(ancestor: ASTNode, descendant: ASTNode): Boolean {
        var current: ASTNode? = descendant
        while (current != null) {
            if (current == ancestor) return true
            current = parentMap[current]
        }
        return false
    }

    /**
     * Clear all tracking data.
     */
    fun clear() {
        nodeStack.clear()
        nodesByUri.clear()
        classNodesByUri.clear()
        parentMap.clear()
        nodeUriMap.clear()
    }

    /**
     * Get the current stack depth for debugging.
     */
    fun getStackDepth(): Int = nodeStack.size
}
