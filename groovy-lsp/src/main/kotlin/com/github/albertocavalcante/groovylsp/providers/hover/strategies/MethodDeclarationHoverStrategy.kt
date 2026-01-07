package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for method declarations.
 */
class MethodDeclarationHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is MethodNode

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is MethodNode) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
