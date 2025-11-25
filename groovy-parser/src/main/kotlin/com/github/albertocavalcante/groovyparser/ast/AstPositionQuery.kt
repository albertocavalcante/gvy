package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.slf4j.LoggerFactory
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
    // TODO: Consider removing this logger once stabilization is complete
    private val logger = LoggerFactory.getLogger(AstPositionQuery::class.java)

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

        if (logger.isDebugEnabled) {
            logger.debug("Searching for node at $groovyLine:$groovyCharacter in ${nodes.size} nodes")
        }

        // Filter nodes that contain the position and find the smallest one
        return nodes.filter { node ->
            val valid = node.hasValidPosition()
            val contains = node.containsPosition(groovyLine, groovyCharacter)
            valid && contains
        }.minWithOrNull(
            compareBy<ASTNode> { node ->
                // 1. Sort by size (smallest first)
                val effectiveLastLine = if (node.lastLineNumber > 0) node.lastLineNumber else node.lineNumber
                val effectiveLastCol = if (node.lastColumnNumber > 0) node.lastColumnNumber else node.columnNumber + 1

                val lineSpan = effectiveLastLine - node.lineNumber
                val charSpan = if (lineSpan == 0) {
                    effectiveLastCol - node.columnNumber
                } else {
                    PositionConstants.MAX_RANGE_SIZE
                }
                lineSpan.toLong() * PositionConstants.LINE_WEIGHT + charSpan.toLong()
            }.thenBy { node ->
                // 2. Tie-breaker: Prefer specific atomic expressions over containers
                // Lower numbers = higher priority (prefer more specific nodes)
                when (node) {
                    is org.codehaus.groovy.ast.expr.VariableExpression -> 0
                    is org.codehaus.groovy.ast.expr.ConstantExpression -> 0
                    is org.codehaus.groovy.ast.expr.GStringExpression -> 0
                    is org.codehaus.groovy.ast.expr.Expression -> 1 // Generic expressions (ArgumentList, MethodCall)
                    is org.codehaus.groovy.ast.stmt.Statement -> 2
                    is org.codehaus.groovy.ast.FieldNode -> 3 // Fields are more specific than methods/classes
                    is org.codehaus.groovy.ast.MethodNode -> 4 // Methods are more specific than classes
                    is org.codehaus.groovy.ast.ClassNode -> 5 // Classes are broad containers
                    else -> 6
                }
            },
        )
    }

    /**
     * Check if a node has valid position information.
     * Relaxed validation: Some nodes (like VariableExpression) might only have start coordinates.
     */
    private fun ASTNode.hasValidPosition(): Boolean = lineNumber > 0 && columnNumber > 0

    /**
     * Check if this node contains the given position using Groovy coordinates.
     * NB: This expects 1-based Groovy coordinates, not 0-based LSP coordinates.
     */
    private fun ASTNode.containsPosition(line: Int, character: Int): Boolean {
        // If we don't have end coordinates, assume it's a single point or small range
        val endLine = if (lastLineNumber > 0) lastLineNumber else lineNumber
        val endColumn = if (lastColumnNumber > 0) lastColumnNumber else columnNumber + 1 // Minimum 1 char width

        // Check if position is within the node's bounds using Groovy 1-based coordinates
        return when {
            line < lineNumber || line > endLine -> false
            line == lineNumber && line == endLine -> {
                // Single line: check column bounds
                character >= columnNumber && character <= endColumn
            }

            line == lineNumber -> {
                // First line: check from column to end of line
                character >= columnNumber
            }

            line == endLine -> {
                // Last line: check from beginning to column
                character <= endColumn
            }

            else -> {
                // Middle lines: always valid
                true
            }
        }
    }
}
