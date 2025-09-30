package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Handles position-based queries on AST nodes.
 * Extracted from AstVisitor to provide focused query functionality.
 *
 * All coordinate operations now use CoordinateSystem for consistency and correctness.
 */
class AstPositionQuery(private val tracker: NodeRelationshipTracker) {

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

        logger.debug("AstPositionQuery: Finding node at $uri, line: $lspLine, char: $lspCharacter")
        logger.debug("AstPositionQuery: Total nodes for URI: ${nodes.size}")

        // Create LSP position for coordinate system
        val lspPosition = CoordinateSystem.LspPosition(lspLine, lspCharacter)
        logger.debug("AstPositionQuery: LSP position: $lspPosition, Groovy position: ${lspPosition.toGroovy()}")

        // Filter nodes that contain the position using the definitive CoordinateSystem implementation
        val candidateNodes = nodes.filter { node ->
            CoordinateSystem.isValidNodePosition(node) && CoordinateSystem.nodeContainsPosition(node, lspPosition)
        }

        logger.debug("AstPositionQuery: Found ${candidateNodes.size} candidate nodes")
        if (candidateNodes.isNotEmpty()) {
            candidateNodes.take(3).forEach { node ->
                logger.debug(
                    "  Candidate: ${node.javaClass.simpleName} at ${CoordinateSystem.getNodePositionDebugString(
                        node,
                    )} - text: ${node.text?.take(30)}",
                )
            }
        } else {
            // Debug: Show why no nodes matched by examining first few nodes
            logger.debug("AstPositionQuery: No candidates found, examining first 5 nodes:")
            nodes.take(5).forEach { node ->
                if (CoordinateSystem.isValidNodePosition(node)) {
                    val contains = CoordinateSystem.nodeContainsPosition(node, lspPosition)
                    logger.debug(
                        "  Rejected: ${node.javaClass.simpleName} at ${CoordinateSystem.getNodePositionDebugString(
                            node,
                        )} - contains: $contains",
                    )
                } else {
                    logger.debug("  Invalid: ${node.javaClass.simpleName} - invalid position")
                }
            }
        }

        val result = candidateNodes.minByOrNull { node ->
            // Calculate node size to find the smallest containing node - use Long to prevent overflow
            val lineSpan = node.lastLineNumber - node.lineNumber
            val charSpan = if (lineSpan == 0) {
                node.lastColumnNumber - node.columnNumber
            } else {
                PositionConstants.MAX_RANGE_SIZE // Multi-line nodes are considered larger
            }
            val baseSize = lineSpan.toLong() * PositionConstants.LINE_WEIGHT + charSpan.toLong()

            // CRITICAL FIX: When nodes have the same size, prefer more specific types
            // Add a priority boost for specific node types that are more meaningful for definitions
            val typePriority = when (node) {
                is org.codehaus.groovy.ast.expr.VariableExpression -> 0 // Highest priority
                is org.codehaus.groovy.ast.expr.DeclarationExpression -> 1
                is org.codehaus.groovy.ast.expr.MethodCallExpression -> 2
                is org.codehaus.groovy.ast.expr.PropertyExpression -> 3
                is org.codehaus.groovy.ast.expr.FieldExpression -> 4
                is org.codehaus.groovy.ast.MethodNode -> 5
                is org.codehaus.groovy.ast.FieldNode -> 6
                is org.codehaus.groovy.ast.PropertyNode -> 7
                is org.codehaus.groovy.ast.ClassNode -> 1000 // Much lower priority
                is org.codehaus.groovy.ast.expr.ArgumentListExpression -> 2000
                is org.codehaus.groovy.ast.stmt.ExpressionStatement -> 3000
                else -> 500
            }

            // Combine base size with type priority (multiply by small factor to maintain size ordering)
            baseSize + (typePriority * 10)
        }

        if (result != null) {
            logger.debug(
                "AstPositionQuery: Selected node: ${result.javaClass.simpleName} - text: ${result.text?.take(50)}",
            )
        } else {
            logger.debug("AstPositionQuery: No node selected")
        }

        return result
    }

    // Position validation and containment logic has been moved to CoordinateSystem
    // This eliminates the duplicate implementations that were causing coordinate system bugs
}
