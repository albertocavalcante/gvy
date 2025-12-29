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
