package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for class declarations.
 */
class ClassDeclarationHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is ClassNode

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is ClassNode) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
