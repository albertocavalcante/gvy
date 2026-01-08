package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for closure expressions.
 *
 * Handles closure expressions with:
 * - Parameters (explicit and implicit 'it')
 * - Delegate type information
 * - Owner context
 * - Variable scope information
 */
class ClosureHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is ClosureExpression

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is ClosureExpression) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
