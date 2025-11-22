package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import java.net.URI

/**
 * Handles position-based queries on AST nodes.
 * Extracted from AstVisitor to provide focused query functionality.
 *
 * NB: Coordinate Systems
 * - Groovy AST: 1-based line and column (line 1 = first line, column 1 = first character)
 * - LSP Protocol: 0-based line and column (line 0 = first line, column 0 = first character)
 * - Conversion: groovyPos = lspPos + 1, lspPos = groovyPos - 1
 * - Invalid positions: Groovy uses -1 for synthetic/invalid nodes
 */
class AstPositionQuery(private val tracker: NodeRelationshipTracker) {

    /**
     * Find the AST node at a specific LSP position.
     */
    fun getNodeAt(uri: URI, lspPosition: Position): ASTNode? = getNodeAt(uri, lspPosition.line, lspPosition.character)

    /**
     * Find the AST node at a specific line and character position.
     * Returns the smallest/most specific node at the given position.
     */
    fun getNodeAt(uri: URI, lspLine: Int, lspCharacter: Int): ASTNode? {
        val nodes = tracker.getNodes(uri)

        // Convert LSP coordinates (0-based) to Groovy coordinates (1-based)
        val groovyLine = lspLine + 1
        val groovyCharacter = lspCharacter + 1

        // Filter nodes that contain the position and find the smallest one
        return nodes.filter { node ->
            node.hasValidPosition() && node.containsPosition(groovyLine, groovyCharacter)
        }.minByOrNull { node ->
            // Calculate node size to find the smallest containing node - use Long to prevent overflow
            val lineSpan = node.lastLineNumber - node.lineNumber
            val charSpan = if (lineSpan == 0) {
                node.lastColumnNumber - node.columnNumber
            } else {
                PositionConstants.MAX_RANGE_SIZE // Multi-line nodes are considered larger
            }
            // CRITICAL FIX: Use Long to prevent integer overflow that was causing ClassNode to have negative size
            lineSpan.toLong() * PositionConstants.LINE_WEIGHT + charSpan.toLong()
        }
    }

    /**
     * Check if a node has valid position information.
     */
    private fun ASTNode.hasValidPosition(): Boolean =
        lineNumber > 0 && columnNumber > 0 && lastLineNumber > 0 && lastColumnNumber > 0

    /**
     * Check if this node contains the given position using Groovy coordinates.
     * NB: This expects 1-based Groovy coordinates, not 0-based LSP coordinates.
     */
    private fun ASTNode.containsPosition(line: Int, character: Int): Boolean {
        // Check if position is within the node's bounds using Groovy 1-based coordinates
        return when {
            line < lineNumber || line > lastLineNumber -> false
            line == lineNumber && line == lastLineNumber -> {
                // Single line: check column bounds
                character >= columnNumber && character <= lastColumnNumber
            }
            line == lineNumber -> {
                // First line: check from column to end of line
                character >= columnNumber
            }
            line == lastLineNumber -> {
                // Last line: check from beginning to column
                character <= lastColumnNumber
            }
            else -> {
                // Middle lines: always valid
                true
            }
        }
    }
}
