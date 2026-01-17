package com.github.albertocavalcante.gvy.semantics.calculator

import arrow.core.fold
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.SemanticType
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Registry for type calculators.
 * Manages a collection of calculators and dispatches to the appropriate one.
 *
 * Thread-safe after initialization.
 */
class TypeCalculatorRegistry private constructor(
    private val calculators: Map<KClass<*>, List<TypeCalculator<*>>>,
    private val superclassCache: Map<KClass<*>, List<KClass<*>>>,
) {

    private val calculatorCache = ConcurrentHashMap<KClass<*>, List<TypeCalculator<*>>>()

    /**
     * Calculate the type of a node, returning Either for explicit error handling.
     *
     * Tries each applicable calculator in priority order until one succeeds.
     *
     * @param node The AST node to calculate type for
     * @param context Resolution context
     * @return Either an error or the calculated type
     */
    fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val nodeClass = node::class
        val applicableCalculators = findApplicableCalculators(nodeClass)

        if (applicableCalculators.isEmpty()) {
            return TypeInferenceError.NoCalculatorFound(nodeClass.simpleName ?: "unknown").left()
        }

        var lastError: TypeInferenceError = TypeInferenceError.NoCalculatorFound(nodeClass.simpleName ?: "unknown")

        for (calc in applicableCalculators) {
            @Suppress("UNCHECKED_CAST")
            val calculator = calc as TypeCalculator<Any>
            val result = calculator.calculateResult(node, context)

            result.fold(
                ifLeft = { lastError = it },
                ifRight = { return it.right() },
            )
        }

        return lastError.left()
    }

    /**
     * Calculate the type of a node.
     * Tries calculators in priority order until one succeeds.
     *
     * @param node The AST node
     * @param context The type context
     * @return The calculated type, or [SemanticType.Unknown] if no calculator could handle it
     */
    fun calculate(node: Any, context: TypeContext): SemanticType = calculateResult(node, context).getOrElse { error ->
        SemanticType.Unknown(error.reason)
    }

    private fun findApplicableCalculators(nodeClass: KClass<*>): List<TypeCalculator<*>> =
        calculatorCache.getOrPut(nodeClass) {
            // Check exact match first
            calculators[nodeClass]?.let { return@getOrPut it }

            // Use pre-computed superclass hierarchy
            val hierarchy = superclassCache[nodeClass] ?: emptyList()
            hierarchy
                .mapNotNull { calculators[it] }
                .flatten()
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

            // Pre-compute superclass hierarchy for all registered types
            val superclassCache = mutableMapOf<KClass<*>, List<KClass<*>>>()
            val allTypes = grouped.keys.toSet()

            for (type in allTypes) {
                val hierarchy = mutableListOf<KClass<*>>()
                collectSuperclasses(type, allTypes, hierarchy)
                if (hierarchy.isNotEmpty()) {
                    superclassCache[type] = hierarchy
                }
            }

            return TypeCalculatorRegistry(grouped, superclassCache)
        }

        private fun collectSuperclasses(
            type: KClass<*>,
            registeredTypes: Set<KClass<*>>,
            result: MutableList<KClass<*>>,
        ) {
            for (registeredType in registeredTypes) {
                if (registeredType != type && registeredType.java.isAssignableFrom(type.java)) {
                    result.add(registeredType)
                }
            }
        }
    }

    companion object {
        /**
         * Create a new builder.
         */
        fun builder(): Builder = Builder()
    }
}
