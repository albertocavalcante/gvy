package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for closure expressions.
 *
 * Phase 2 (AST-agnostic): treat closures as `groovy.lang.Closure`.
 */
class ClosureExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        if (node::class.java.simpleName != "ClosureExpression") {
            return TypeInferenceError.UnsupportedNode(
                nodeType = node::class.simpleName ?: "unknown (not ClosureExpression)",
            ).left()
        }
        return TypeConstants.CLOSURE.right()
    }
}
