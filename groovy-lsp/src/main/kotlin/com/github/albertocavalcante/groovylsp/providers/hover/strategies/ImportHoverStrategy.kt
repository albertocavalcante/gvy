package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ImportNode
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for import statements.
 */
class ImportHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is ImportNode

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is ImportNode) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
