package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.eclipse.lsp4j.Hover

/**
 * Strategy for generating hover information for constructor calls.
 *
 * Handles constructor invocations like:
 * - `new ArrayList<String>()` - Generic constructor
 * - `new Person("Alice", 30)` - Constructor with parameters
 * - `new Config(host: "localhost", port: 8080)` - Constructor with named arguments
 * - `new Outer.Inner()` - Nested class constructor
 */
class ConstructorCallHoverStrategy : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = node is ConstructorCallExpression

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is ConstructorCallExpression) return null

        return context.contentGenerator
            .generateHover(node, context.moduleNode)
            .getOrNull()
    }
}
