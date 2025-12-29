package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.Range
import java.util.Optional

/**
 * Base class for all AST nodes.
 * Provides common functionality for tree navigation, position tracking, and visiting.
 */
abstract class Node {
    /** The parent node in the AST, or null if this is the root */
    var parentNode: Node? = null
        internal set

    /** The source range of this node */
    var range: Range? = null
        internal set

    /** Annotations on this node */
    val annotations: MutableList<AnnotationExpr> = mutableListOf()

    /**
     * Returns the parent node wrapped in an Optional.
     */
    fun getParentNode(): Optional<Node> = Optional.ofNullable(parentNode)

    /**
     * Returns the range wrapped in an Optional.
     */
    fun getRange(): Optional<Range> = Optional.ofNullable(range)

    /**
     * Returns all direct child nodes.
     */
    abstract fun getChildNodes(): List<Node>

    /**
     * Adds an annotation to this node.
     */
    fun addAnnotation(annotation: AnnotationExpr) {
        annotations.add(annotation)
        setAsParentNodeOf(annotation)
    }

    /**
     * Gets an annotation by name, or null if not present.
     */
    fun getAnnotation(name: String): AnnotationExpr? =
        annotations.find { it.name == name || it.name.endsWith(".$name") }

    /**
     * Returns true if this node has the specified annotation.
     */
    fun hasAnnotation(name: String): Boolean = getAnnotation(name) != null

    /**
     * Returns true if this node is marked with @NonCPS (Jenkins).
     */
    val isNonCps: Boolean
        get() = hasAnnotation("NonCPS")

    /**
     * Sets the parent node for this node and all its children.
     */
    protected fun setAsParentNodeOf(child: Node?) {
        child?.parentNode = this
    }

    /**
     * Sets the parent node for all nodes in the list.
     */
    protected fun setAsParentNodeOf(children: List<Node>?) {
        children?.forEach { it.parentNode = this }
    }
}
