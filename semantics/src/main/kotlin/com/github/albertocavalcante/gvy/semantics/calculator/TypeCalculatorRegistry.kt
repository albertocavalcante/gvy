package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.SemanticType
import kotlin.reflect.KClass

/**
 * Registry for type calculators.
 * Manages a collection of calculators and dispatches to the appropriate one.
 *
 * Thread-safe after initialization.
 */
class TypeCalculatorRegistry private constructor(private val calculators: Map<KClass<*>, List<TypeCalculator<*>>>) {

    /**
     * Calculate the type of a node.
     * Tries calculators in priority order until one succeeds.
     *
     * @param node The AST node
     * @param context The type context
     * @return The calculated type, or [SemanticType.Unknown] if no calculator could handle it
     */
    fun calculate(node: Any, context: TypeContext): SemanticType {
        val nodeClass = node::class

        // Find calculators for this node type (including superclasses)
        val applicableCalculators = findApplicableCalculators(nodeClass)

        val result = applicableCalculators.firstNotNullOfOrNull {
            @Suppress("UNCHECKED_CAST")
            val calc = it as TypeCalculator<Any>
            calc.calculate(node, context)
        }

        return result ?: SemanticType.Unknown("No calculator found for ${nodeClass.simpleName}")
    }

    private fun findApplicableCalculators(nodeClass: KClass<*>): List<TypeCalculator<*>> {
        // Check exact match first
        calculators[nodeClass]?.let { return it }

        // Check superclasses and interfaces
        return calculators.entries
            .filter { (key, _) -> key.java.isAssignableFrom(nodeClass.java) }
            .flatMap { it.value }
            .sortedByDescending { it.priority }
    }

    /**
     * Builder for creating a registry.
     */
    class Builder {
        private val calculators = mutableListOf<TypeCalculator<*>>()

        /**
         * Register a calculator.
         */
        fun <T : Any> register(calculator: TypeCalculator<T>): Builder {
            calculators.add(calculator)
            return this
        }

        /**
         * Build the registry.
         */
        fun build(): TypeCalculatorRegistry {
            val grouped = calculators
                .groupBy { it.nodeType }
                .mapValues { (_, calcs) -> calcs.sortedByDescending { it.priority } }
            return TypeCalculatorRegistry(grouped)
        }
    }

    companion object {
        /**
         * Create a new builder.
         */
        fun builder(): Builder = Builder()
    }
}
