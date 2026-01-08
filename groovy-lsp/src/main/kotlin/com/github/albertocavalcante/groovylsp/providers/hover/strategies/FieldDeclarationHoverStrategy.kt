package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.FieldNode
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for field declarations.
 */
class FieldDeclarationHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is FieldNode

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is FieldNode) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
