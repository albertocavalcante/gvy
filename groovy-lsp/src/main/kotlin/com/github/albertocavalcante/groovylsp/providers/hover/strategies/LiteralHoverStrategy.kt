package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for literal expressions.
 *
 * Handles literals including:
 * - String literals (`"hello"`)
 * - Integer literals (`42`)
 * - Decimal literals (`3.14`)
 * - Boolean literals (`true`, `false`)
 * - List literals (`[1, 2, 3]`)
 * - Map literals (`[a: 1, b: 2]`)
 * - GString literals (`"Hello, ${name}"`)
 * - Null (`null`)
 */
class LiteralHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is ConstantExpression ||
        node is GStringExpression ||
        node is ListExpression ||
        node is MapExpression

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? = when (node) {
        is ConstantExpression,
        is GStringExpression,
        is ListExpression,
        is MapExpression,
        -> context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()

        else -> null
    }
}
