package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Hover

/**
 * Fallback strategy for generating hover information for any AST node.
 * This should be the last strategy in the chain as it accepts all nodes.
 */
class GenericHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean {
        return true // Accept any node
    }

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? = context.contentGenerator
        .generateHover(node, context.moduleNode)
        .getOrNull()
}
