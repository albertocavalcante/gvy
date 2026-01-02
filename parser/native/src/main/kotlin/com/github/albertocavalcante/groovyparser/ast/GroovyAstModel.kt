package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import java.net.URI

/**
 * Common interface for AST models that provide node lookup and relationship tracking.
 * Implemented by [RecursiveAstVisitor].
 */
// TODO(#367): Add tree-sitter-style S-expression AST query support.
//   See: https://github.com/albertocavalcante/groovy-devtools/issues/367
interface GroovyAstModel {
    /**
     * Returns the parent node of the given AST node.
     */
    fun getParent(node: ASTNode): ASTNode?

    /**
     * Returns the direct children of the given AST node.
     */
    fun getChildren(node: ASTNode): List<ASTNode>

    /**
     * Returns the source URI associated with the given AST node.
     */
    fun getUri(node: ASTNode): URI?

    /**
     * Returns all nodes for a specific URI.
     */
    fun getNodes(uri: URI): List<ASTNode>

    /**
     * Returns all AST nodes tracked.
     */
    fun getAllNodes(): List<ASTNode>

    /**
     * Returns all class nodes tracked.
     */
    fun getAllClassNodes(): List<ClassNode>

    /**
     * Finds the specific AST node at the given LSP position.
     */
    fun getNodeAt(uri: URI, position: Position): ASTNode?

    /**
     * Finds the specific AST node at the given LSP coordinates.
     */
    fun getNodeAt(uri: URI, line: Int, character: Int): ASTNode?

    /**
     * Checks if an ancestor node contains a descendant node in the tree.
     */
    fun contains(ancestor: ASTNode, descendant: ASTNode): Boolean
}
