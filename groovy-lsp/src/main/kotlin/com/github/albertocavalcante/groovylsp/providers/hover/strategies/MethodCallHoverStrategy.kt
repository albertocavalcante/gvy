package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for method calls.
 */
class MethodCallHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is MethodCallExpression

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is MethodCallExpression) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
