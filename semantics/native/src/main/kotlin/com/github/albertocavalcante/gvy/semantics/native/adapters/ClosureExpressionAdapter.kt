package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import org.codehaus.groovy.ast.expr.ClosureExpression
import kotlin.reflect.KClass

/**
 * Calculator for ClosureExpression nodes.
 * Always returns Closure type (detailed return type inference is Phase 6+).
 */
object ClosureExpressionAdapter : TypeCalculator<ClosureExpression> {

    override val nodeType: KClass<ClosureExpression> = ClosureExpression::class
    override val priority: Int = NativeCalculators.SCOPE_AWARE_PRIORITY

    override fun calculate(node: ClosureExpression, context: TypeContext): SemanticType {
        // For now, return generic Closure type
        // TODO: Infer return type from last expression in body
        return TypeConstants.CLOSURE
    }
}
