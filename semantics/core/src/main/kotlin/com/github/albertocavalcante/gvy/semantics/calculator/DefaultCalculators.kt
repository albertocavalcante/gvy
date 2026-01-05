package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.calculator.impl.BinaryExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ClosureExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ConstantExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.DeclarationExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ElvisOperatorExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.GStringExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.ListExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.MapExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.MethodCallCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.PropertyAccessCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.TernaryExpressionCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.impl.VariableExpressionCalculator

/**
 * Factory for creating a registry with all default calculators.
 */
object DefaultCalculators {
    /**
     * Creates a default TypeCalculatorRegistry with core calculators registered.
     */
    fun createDefaultRegistry(): TypeCalculatorRegistry = TypeCalculatorRegistry.Builder()
        .register(ConstantExpressionCalculator())
        .register(GStringExpressionCalculator())
        .register(VariableExpressionCalculator())
        .register(ClosureExpressionCalculator())
        .register(DeclarationExpressionCalculator())
        .register(BinaryExpressionCalculator())
        .register(TernaryExpressionCalculator())
        .register(ElvisOperatorExpressionCalculator())
        .register(ListExpressionCalculator())
        .register(MapExpressionCalculator())
        .register(MethodCallCalculator())
        .register(PropertyAccessCalculator())
        .build()
}
