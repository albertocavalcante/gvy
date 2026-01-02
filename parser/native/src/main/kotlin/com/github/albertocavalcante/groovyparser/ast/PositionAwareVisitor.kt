package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression

/**
 * Position-aware visitor for finding the smallest AST node at a specific position.
 * Extracted from AstPositionExtensions.kt to reduce file function count.
 */

/**
 * Node priority for resolving conflicts when multiple nodes cover the same position.
 * Higher weights indicate higher priority.
 */
private sealed class NodePriority(val weight: Int) {
    companion object {
        private const val LITERAL_PRIORITY = 0
        private const val REFERENCE_PRIORITY = 1
        private const val CALL_PRIORITY = 2
        private const val DECLARATION_PRIORITY = 3
        private const val DEFINITION_PRIORITY = 4
    }

    object Literal : NodePriority(LITERAL_PRIORITY)
    object Reference : NodePriority(REFERENCE_PRIORITY)
    object Call : NodePriority(CALL_PRIORITY)
    object Declaration : NodePriority(DECLARATION_PRIORITY)
    object Definition : NodePriority(DEFINITION_PRIORITY)
}

/**
 * Get the priority of this AST node for selection purposes.
 */
private fun ASTNode.priority(): NodePriority = when (this) {
    is MethodNode, is ClassNode, is FieldNode, is PropertyNode -> NodePriority.Definition
    is GStringExpression -> NodePriority.Definition
    is org.codehaus.groovy.ast.expr.ConstantExpression -> NodePriority.Definition
    is DeclarationExpression -> NodePriority.Declaration
    is BinaryExpression -> NodePriority.Declaration
    is MethodCallExpression -> NodePriority.Call
    is org.codehaus.groovy.ast.expr.VariableExpression -> NodePriority.Reference
    else -> NodePriority.Literal
}

/**
 * Calculate the size of the range covered by this AST node.
 */
private fun ASTNode.hasInvalidPosition(): Boolean = lineNumber <= 0 ||
    columnNumber <= 0 ||
    lastLineNumber <= 0 ||
    lastColumnNumber <= 0

private fun ASTNode.calculateRangeSize(): Int {
    if (hasInvalidPosition()) {
        return Int.MAX_VALUE // Invalid position should have lowest priority
    }

    val lineSpan = (lastLineNumber - lineNumber)
    val columnSpan = if (lineSpan == 0) {
        lastColumnNumber - columnNumber
    } else {
        // Multi-line nodes: approximate size
        lineSpan * PositionConstants.MULTI_LINE_WEIGHT + lastColumnNumber
    }

    return lineSpan * PositionConstants.LINE_WEIGHT + columnSpan
}
internal class PositionAwareVisitor(private val targetLine: Int, private val targetColumn: Int) {
    var smallestNode: ASTNode? = null
    private var smallestRangeSize = Int.MAX_VALUE
    private val nodeVisitor = com.github.albertocavalcante.groovyparser.ast.visitor.PositionNodeVisitor(this)

    // Core API (10 functions maximum)
    fun visitModule(module: ModuleNode) {
        nodeVisitor.visitModule(module)
    }

    internal fun checkAndUpdateSmallest(node: ASTNode) {
        if (node.containsPosition(targetLine, targetColumn)) {
            val rangeSize = node.calculateRangeSize()
            if (shouldReplace(rangeSize, node)) {
                smallestNode = node
                smallestRangeSize = rangeSize
            }
        }
    }

    private fun shouldReplace(rangeSize: Int, node: ASTNode): Boolean = when {
        smallestNode == null -> true
        rangeSize < smallestRangeSize -> true
        rangeSize == smallestRangeSize -> {
            val currentPriority = node.priority().weight
            val existingPriority = smallestNode!!.priority().weight
            currentPriority > existingPriority
        }
        else -> false
    }

    // Utility methods
    fun reset() {
        smallestNode = null
        smallestRangeSize = Int.MAX_VALUE
    }

    fun hasResult() = smallestNode != null
    fun getResult() = smallestNode
    fun getRangeSize() = smallestRangeSize
}
