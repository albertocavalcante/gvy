package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for property access expressions.
 *
 * Handles expressions like:
 * - `object.property` - Simple property access
 * - `map.key` - Map key access
 * - `person.address.city` - Nested property access
 * - `object?.property` - Safe navigation
 */
class PropertyExpressionHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is PropertyExpression

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is PropertyExpression) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
