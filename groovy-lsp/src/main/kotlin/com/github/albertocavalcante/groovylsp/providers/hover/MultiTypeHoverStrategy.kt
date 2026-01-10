package com.github.albertocavalcante.groovylsp.providers.hover

import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Hover
import kotlin.reflect.KClass

// TODO(#807): Add test coverage for MultiTypeHoverStrategy base class.
//   See: https://github.com/albertocavalcante/gvy/issues/807
/**
 * Base class for hover strategies that handle multiple AST node types.
 *
 * This is useful for strategies that need to handle a group of related node types
 * with the same hover generation logic (e.g., different literal types).
 *
 * Example:
 * ```kotlin
 * class LiteralHoverStrategy : MultiTypeHoverStrategy(
 *     ConstantExpression::class,
 *     GStringExpression::class,
 *     ListExpression::class,
 *     MapExpression::class
 * )
 * ```
 *
 * @param nodeTypes The KClass instances of all node types this strategy handles
 */
abstract class MultiTypeHoverStrategy(private vararg val nodeTypes: KClass<out ASTNode>) : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = nodeTypes.any { it.isInstance(node) }

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (!canHandle(node)) return null
        return generateMultiTypeHover(node, context)
    }

    /**
     * Generate hover content for any of the supported node types.
     * Default implementation delegates to the content generator.
     *
     * Override this method if custom hover generation logic is needed.
     *
     * @param node The AST node (one of the supported types)
     * @param context Additional context needed for hover generation
     * @return Hover information, or null if unable to generate
     */
    protected open fun generateMultiTypeHover(node: ASTNode, context: HoverContext): Hover? =
        context.contentGenerator.generateHover(node, context.moduleNode).getOrNull()
}
