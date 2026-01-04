package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import org.codehaus.groovy.ast.expr.VariableExpression
import kotlin.reflect.KClass

/**
 * Calculator for VariableExpression nodes.
 * Uses scope lookup to resolve variable types.
 */
object VariableExpressionAdapter : TypeCalculator<VariableExpression> {

    override val nodeType: KClass<VariableExpression> = VariableExpression::class
    override val priority: Int = NativeCalculators.SCOPE_AWARE_PRIORITY // Higher than reflection-based fallback

    override fun calculate(node: VariableExpression, context: TypeContext): SemanticType? {
        // Try scope lookup first
        context.lookupSymbol(node.name)?.let { return it }

        // Fall back to access type if available
        val accessedVariable = node.accessedVariable
        if (accessedVariable != null && accessedVariable !== node) {
            return context.calculateType(accessedVariable)
        }

        return SemanticType.Dynamic("unresolved variable: ${node.name}")
    }
}
