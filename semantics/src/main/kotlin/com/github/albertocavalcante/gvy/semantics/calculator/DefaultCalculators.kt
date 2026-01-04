package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.calculator.impl.BinaryExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ConstantExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ListExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.MapExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.MethodCallCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.PropertyAccessCalculator

/**
 * Factory for creating a registry with all default calculators.
 */
object DefaultCalculators {
    /**
     * Creates a default TypeCalculatorRegistry with core calculators registered.
     */
    fun createDefaultRegistry(): TypeCalculatorRegistry = TypeCalculatorRegistry.Builder()
        .register(ConstantExpressionCalculator())
        .register(BinaryExpressionCalculator())
        .register(ListExpressionCalculator())
        .register(MapExpressionCalculator())
        .register(MethodCallCalculator())
        .register(PropertyAccessCalculator())
        .build()
}
