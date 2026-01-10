package com.github.albertocavalcante.groovylsp.providers.hover

import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Hover
import kotlin.reflect.KClass

/**
 * Type-safe base class for hover strategies that handle a single AST node type.
 *
 * This eliminates boilerplate by providing default implementations of canHandle
 * and generateHover with automatic type checking and casting.
 *
 * Subclasses only need to:
 * 1. Extend this class with the appropriate node type
 * 2. Optionally override generateTypedHover if custom logic is needed
 *
 * Example:
 * ```kotlin
 * class MethodNodeHoverStrategy : TypedHoverStrategy<MethodNode>(MethodNode::class)
 * ```
 *
 * @param T The specific ASTNode type this strategy handles
 * @param nodeType The KClass of the node type (used for runtime type checking)
 */
abstract class TypedHoverStrategy<T : ASTNode>(private val nodeType: KClass<T>) : HoverStrategy {
    override fun canHandle(node: ASTNode): Boolean = nodeType.isInstance(node)

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (!nodeType.isInstance(node)) return null
        @Suppress("UNCHECKED_CAST")
        return generateTypedHover(node as T, context)
    }

    /**
     * Generate hover content for the strongly-typed node.
     * Default implementation delegates to the content generator.
     *
     * Override this method if custom hover generation logic is needed.
     *
     * @param node The AST node (already cast to the correct type)
     * @param context Additional context needed for hover generation
     * @return Hover information, or null if unable to generate
     */
    protected open fun generateTypedHover(node: T, context: HoverContext): Hover? =
        context.contentGenerator.generateHover(node, context.moduleNode).getOrNull()
}
