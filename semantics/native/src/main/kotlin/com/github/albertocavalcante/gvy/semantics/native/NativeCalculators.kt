package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry
import com.github.albertocavalcante.gvy.semantics.calculator.impl.BinaryExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ConstantExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ConstructorCallExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ListExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.MapExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.MethodCallCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.PropertyAccessCalculator
import com.github.albertocavalcante.gvy.semantics.native.adapters.ClosureExpressionAdapter
import com.github.albertocavalcante.gvy.semantics.native.adapters.DeclarationExpressionAdapter
import com.github.albertocavalcante.gvy.semantics.native.adapters.VariableExpressionAdapter

/**
 * Factory for creating a registry with native Groovy AST support.
 *
 * Extends DefaultCalculators (reflection-based) with scope-aware calculators
 * that require native AST access.
 */
object NativeCalculators {

    /**
     * Create a registry with all calculators for native Groovy AST.
     *
     * Priority order:
     * 1. Scope-aware native adapters (priority 20)
     * 2. Reflection-based default calculators (priority 0)
     */
    const val SCOPE_AWARE_PRIORITY = 20

    fun createRegistry(): TypeCalculatorRegistry = TypeCalculatorRegistry.builder()
        // Scope-aware calculators (higher priority)
        .register(VariableExpressionAdapter)
        .register(DeclarationExpressionAdapter)
        .register(ClosureExpressionAdapter)
        // Reflection-based calculators from Phase 2
        .register(ConstantExpressionCalculator())
        .register(ConstructorCallExpressionCalculator())
        .register(BinaryExpressionCalculator())
        .register(ListExpressionCalculator())
        .register(MapExpressionCalculator())
        .register(MethodCallCalculator())
        .register(PropertyAccessCalculator())
        .build()
}
