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

    companion object {
        // Debug output limits
        private const val MAX_DEBUG_CANDIDATES_TO_SHOW = 3
        private const val MAX_DEBUG_REJECTED_TO_SHOW = 5

        // Text truncation for debug messages
        private const val DEBUG_TEXT_PREVIEW_LENGTH = 30
        private const val DEBUG_TEXT_MAX_LENGTH = 50

        // Node type priorities for position selection (lower = higher priority)
        private const val VARIABLE_EXPRESSION_PRIORITY = 0
        private const val DECLARATION_EXPRESSION_PRIORITY = 1
        private const val METHOD_CALL_EXPRESSION_PRIORITY = 2
        private const val PROPERTY_EXPRESSION_PRIORITY = 3
        private const val FIELD_EXPRESSION_PRIORITY = 4
        private const val METHOD_NODE_PRIORITY = 5
        private const val FIELD_NODE_PRIORITY = 6
        private const val PROPERTY_NODE_PRIORITY = 7
        private const val DEFAULT_NODE_PRIORITY = 500
        private const val CLASS_NODE_PRIORITY = 1000
        private const val ARGUMENT_LIST_EXPRESSION_PRIORITY = 2000
        private const val EXPRESSION_STATEMENT_PRIORITY = 3000

        // Priority calculation
        private const val TYPE_PRIORITY_MULTIPLIER = 10
    }

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
            candidateNodes.take(MAX_DEBUG_CANDIDATES_TO_SHOW).forEach { node ->
                logger.debug(
                    "  Candidate: ${node.javaClass.simpleName} at ${CoordinateSystem.getNodePositionDebugString(
                        node,
                    )} - text: ${node.text?.take(DEBUG_TEXT_PREVIEW_LENGTH)}",
                )
            }
        } else {
            // Debug: Show why no nodes matched by examining first few nodes
            logger.debug("AstPositionQuery: No candidates found, examining first $MAX_DEBUG_REJECTED_TO_SHOW nodes:")
            nodes.take(MAX_DEBUG_REJECTED_TO_SHOW).forEach { node ->
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
                is org.codehaus.groovy.ast.expr.VariableExpression -> VARIABLE_EXPRESSION_PRIORITY // Highest priority
                is org.codehaus.groovy.ast.expr.DeclarationExpression -> DECLARATION_EXPRESSION_PRIORITY
                is org.codehaus.groovy.ast.expr.MethodCallExpression -> METHOD_CALL_EXPRESSION_PRIORITY
                is org.codehaus.groovy.ast.expr.PropertyExpression -> PROPERTY_EXPRESSION_PRIORITY
                is org.codehaus.groovy.ast.expr.FieldExpression -> FIELD_EXPRESSION_PRIORITY
                is org.codehaus.groovy.ast.MethodNode -> METHOD_NODE_PRIORITY
                is org.codehaus.groovy.ast.FieldNode -> FIELD_NODE_PRIORITY
                is org.codehaus.groovy.ast.PropertyNode -> PROPERTY_NODE_PRIORITY
                is org.codehaus.groovy.ast.ClassNode -> CLASS_NODE_PRIORITY // Much lower priority
                is org.codehaus.groovy.ast.expr.ArgumentListExpression -> ARGUMENT_LIST_EXPRESSION_PRIORITY
                is org.codehaus.groovy.ast.stmt.ExpressionStatement -> EXPRESSION_STATEMENT_PRIORITY
                else -> DEFAULT_NODE_PRIORITY
            }

            // Combine base size with type priority (multiply by small factor to maintain size ordering)
            baseSize + (typePriority * TYPE_PRIORITY_MULTIPLIER)
        }

        if (result != null) {
            logger.debug(
                "AstPositionQuery: Selected node: ${result.javaClass.simpleName} - text: ${result.text?.take(
                    DEBUG_TEXT_MAX_LENGTH,
                )}",
            )
        } else {
            logger.debug("AstPositionQuery: No node selected")
        }

        return result
    }

    // Position validation and containment logic has been moved to CoordinateSystem
    // This eliminates the duplicate implementations that were causing coordinate system bugs
}
