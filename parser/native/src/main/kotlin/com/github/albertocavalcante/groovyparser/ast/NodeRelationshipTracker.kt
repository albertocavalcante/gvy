package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks parent-child relationships between AST nodes and their URI mappings.
 * Extracted from the original visitor implementation to provide focused responsibility.
 */
class NodeRelationshipTracker {
    private val logger = LoggerFactory.getLogger(NodeRelationshipTracker::class.java)

    // Parent tracking using a stack - following fork-groovy pattern
    private val nodeStack = ArrayDeque<ASTNode>()

    // Node storage per URI for cross-file support - using thread-safe collections
    private val nodesByUri = ConcurrentHashMap<URI, MutableCollection<ASTNode>>()
    private val classNodesByUri = ConcurrentHashMap<URI, MutableCollection<ClassNode>>()

    // Module storage - source of truth for class definitions
    private val modulesByUri = ConcurrentHashMap<URI, ModuleNode>()

    // Parent-child relationships
    // NOTE: Tradeoff:
    // Use identity semantics for ASTNode keys to avoid collisions from overridden equals/hashCode (e.g., ClassNode).
    // Synchronization is sufficient here because writes happen during compilation and reads are short-lived queries.
    private val parentMap = Collections.synchronizedMap(IdentityHashMap<ASTNode, ASTNode>())
    private val childrenMap = Collections.synchronizedMap(IdentityHashMap<ASTNode, OrderedIdentitySet<ASTNode>>())

    // URI mapping for nodes
    // NOTE: Tradeoff:
    // Same rationale as parentMap: we need stable, identity-based node->URI mapping across the tracked node graph.
    private val nodeUriMap = Collections.synchronizedMap(IdentityHashMap<ASTNode, URI>())

    /**
     * Push a node onto the tracking stack and establish relationships.
     */
    fun pushNode(node: ASTNode, currentUri: URI?) {
        // Track parent-child relationship
        if (nodeStack.isNotEmpty()) {
            val parent = nodeStack.last()
            parentMap[node] = parent
            childrenMap.computeIfAbsent(parent) { OrderedIdentitySet() }.add(node)
        }

        // Add to stack
        nodeStack.addLast(node)

        // Store by URI if available
        currentUri?.let { uri ->
            nodeUriMap[node] = uri

            // Add to nodes collection (use identity-based Set to avoid ClassNode.equals() deduplication)
            val added = nodesByUri.computeIfAbsent(uri) {
                Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
            }.add(node)

            if (node is ClassNode) {
                if (logger.isDebugEnabled) {
                    // NOTE: Stdout is reserved for JSON-RPC in stdio mode; debug output must go through the logger.
                    logger.debug(
                        "[pushNode] ClassNode {} @ {}:{}, id={}, added={}",
                        node.name,
                        node.lineNumber,
                        node.columnNumber,
                        System.identityHashCode(node),
                        added,
                    )
                }
            }

            // Track class nodes separately for quick access (use identity-based Set)
            if (node is ClassNode) {
                classNodesByUri.computeIfAbsent(uri) {
                    Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
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
     * Get the direct children of the specified node.
     */
    fun getChildren(node: ASTNode): List<ASTNode> = childrenMap[node]?.toList() ?: emptyList()

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
     * Store the ModuleNode for a URI.
     * This is used as the source of truth for class definitions.
     */
    fun setModuleNode(uri: URI, module: ModuleNode) {
        modulesByUri[uri] = module
    }

    /**
     * Get all class nodes from all URIs.
     *
     * Returns ONLY class definitions (not type references like imports, superclasses, constructor types).
     * Uses ModuleNode.classes as the source of truth - zero heuristics, deterministic behavior.
     *
     * This approach eliminates:
     * - Type references tracked for navigation (e.g., Person ClassNode at constructor call site)
     * - Library classes (java.*, groovy.*, etc.)
     * - Script wrapper classes (unless user-defined)
     *
     * The Groovy compiler maintains ModuleNode.classes as the definitive list of class declarations,
     * so we leverage that instead of heuristic filtering.
     */
    fun getAllClassNodes(): List<ClassNode> {
        val result = modulesByUri.values.flatMap { module ->
            collectClassDefinitions(module)
        }
        if (logger.isDebugEnabled) {
            // NOTE: Stdout is reserved for JSON-RPC in stdio mode; debug output must go through the logger.
            logger.debug("[getAllClassNodes] Found ${result.size} classes:")
            result.forEach { cls ->
                logger.debug(
                    "  - ${cls.name} @ Line ${cls.lineNumber}:${cls.columnNumber}, isScript=${cls.isScript}",
                )
            }
        }
        return result
    }

    /**
     * Collect all class definitions from a module, including nested/inner classes.
     */
    private fun collectClassDefinitions(module: ModuleNode): List<ClassNode> = module.classes.flatMap { classNode ->
        // Include the class itself and all its inner classes recursively
        listOf(classNode) + collectInnerClasses(classNode)
    }

    /**
     * Recursively collect all inner classes from a ClassNode.
     */
    private fun collectInnerClasses(classNode: ClassNode): List<ClassNode> {
        val result = mutableListOf<ClassNode>()
        val innerIterator = classNode.innerClasses
        if (innerIterator != null) {
            while (innerIterator.hasNext()) {
                val innerClass = innerIterator.next()
                result.add(innerClass)
                result.addAll(collectInnerClasses(innerClass))
            }
        }
        return result
    }

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
        modulesByUri.clear()
        parentMap.clear()
        childrenMap.clear()
        nodeUriMap.clear()
    }

    /**
     * Get the current stack depth for debugging.
     */
    fun getStackDepth(): Int = nodeStack.size

    private class OrderedIdentitySet<T : Any> {
        private val index = IdentityHashMap<T, Unit>()
        private val items = mutableListOf<T>()
        private val lock = Any()

        fun add(item: T) {
            synchronized(lock) {
                if (index.containsKey(item)) return
                index[item] = Unit
                items.add(item)
            }
        }

        fun toList(): List<T> = synchronized(lock) {
            items.toList()
        }
    }
}
