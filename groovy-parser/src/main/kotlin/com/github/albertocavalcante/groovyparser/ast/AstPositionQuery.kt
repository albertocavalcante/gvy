package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Handles position-based queries on AST nodes.
 * Extracted from the original visitor implementation to provide focused query functionality.
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
            // NOTE: Stdout is reserved for JSON-RPC in stdio mode; debug output must go through the logger.
            val classNodes = nodes.filterIsInstance<org.codehaus.groovy.ast.ClassNode>()
            logger.debug("[getNodeAt] LSP($lspLine, $lspCharacter) -> Groovy($groovyLine, $groovyCharacter)")
            logger.debug("[getNodeAt] Total nodes tracked: ${nodes.size}")
            logger.debug("[getNodeAt] ClassNodes tracked:")
            classNodes.forEach { cls ->
                logger.debug("  - ${cls.name} @ ${cls.lineNumber}:${cls.columnNumber}")
            }
        }

        if (logger.isDebugEnabled) {
            logger.debug("Searching for node at $groovyLine:$groovyCharacter in ${nodes.size} nodes")
        }

        // Filter nodes that contain the position and find the smallest one.
        val matchingNodes = nodes.filter { node ->
            CoordinateSystem.nodeContainsPositionRelaxed(node, lspLine, lspCharacter) {
                it.tokenLengthHint()
            }
        }

        // NOTE: Heuristic / tradeoff:
        // ModuleNode positions are frequently unreliable (often reflecting only the first statement),
        // but we still track them to preserve parent relationships. When selecting a node at a cursor
        // position, returning ModuleNode is rarely helpful (hover/definition/references) and can
        // shadow more specific nodes. Prefer non-Module nodes when available.
        val candidatesWithoutModule = matchingNodes
            .filterNot { it is ModuleNode }
            .ifEmpty { matchingNodes }

        // NOTE: Heuristic / tradeoff:
        // Statement nodes (BlockStatement/ExpressionStatement/etc.) often have coarse or misleading ranges in
        // Groovy ASTs. For symbol-oriented features we usually want an expression/declaration node instead.
        // If there are any non-statement candidates, prefer them.
        val candidates = candidatesWithoutModule
            .filterNot { it is org.codehaus.groovy.ast.stmt.Statement }
            .ifEmpty { candidatesWithoutModule }

        return candidates.minWithOrNull(
            compareBy<ASTNode> { node ->
                // 1. Sort by size (smallest first)
                val effectiveLastLine = if (node.lastLineNumber > 0) node.lastLineNumber else node.lineNumber
                val effectiveLastCol = if (node.lastColumnNumber > 0) {
                    node.lastColumnNumber
                } else {
                    val tokenLen = node.tokenLengthHint()
                    if (tokenLen != null && tokenLen > 0) {
                        node.columnNumber + tokenLen - 1
                    } else {
                        node.columnNumber + 1
                    }
                }

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

    private fun ASTNode.tokenLengthHint(): Int? = when (this) {
        is org.codehaus.groovy.ast.ClassNode -> {
            val nameLen = this.nameWithoutPackage.length
            // NOTE: Heuristic / tradeoff:
            // Some Groovy ClassNodes use the declaration start column (e.g. `public class`) rather than the name.
            // Expand slightly so that clicking/hovering on the class name still resolves reliably.
            // TODO: Replace with deterministic token span (requires parser-provided offsets or tokenization).
            if (this.columnNumber <= 20) nameLen + 32 else nameLen
        }
        is org.codehaus.groovy.ast.expr.VariableExpression ->
            when (this.name) {
                // Avoid widening implicit receivers like `this` / `super`, which can otherwise "steal" the cursor
                // from adjacent call sites (e.g., hovering `println(...)` returning hover for `this`).
                "this",
                "super",
                -> null

                else -> this.name.length
            }
        is org.codehaus.groovy.ast.expr.ConstructorCallExpression -> this.type.nameWithoutPackage.length
        is org.codehaus.groovy.ast.expr.ClassExpression -> this.type.nameWithoutPackage.length
        is org.codehaus.groovy.ast.expr.MethodCallExpression -> {
            val methodName =
                when (val methodExpr = this.method) {
                    is org.codehaus.groovy.ast.expr.ConstantExpression -> methodExpr.text
                    is org.codehaus.groovy.ast.expr.VariableExpression -> methodExpr.name
                    else -> null
                }
            (methodName?.length ?: 0) + 1
        }
        is org.codehaus.groovy.ast.ImportNode -> {
            this.type?.nameWithoutPackage?.length
                ?: this.className?.substringAfterLast('.')?.length
        }
        else -> null
    }
}
