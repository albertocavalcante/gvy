package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.SemanticType
import kotlin.reflect.KClass

/**
 * Interface for type calculators that infer types from AST nodes.
 *
 * Inspired by IntelliJ's GrTypeCalculator pattern.
 * Each calculator handles a specific node type.
 *
 * @param T The AST node type this calculator handles
 */
interface TypeCalculator<T : Any> {

    /**
     * The node type this calculator handles.
     */
    val nodeType: KClass<T>

    /**
     * Priority of this calculator. Higher = tried first.
     * Default is 0. Use negative for fallbacks.
     */
    val priority: Int get() = 0

    /**
     * Calculate the type of the given node.
     *
     * @param node The AST node to calculate type for
     * @param context Resolution context (scope, type solver, etc.)
     * @return The calculated type, or null if this calculator cannot handle it
     */
    fun calculate(node: T, context: TypeContext): SemanticType?
}
