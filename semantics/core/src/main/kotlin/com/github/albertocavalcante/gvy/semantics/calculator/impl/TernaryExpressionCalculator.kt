package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for ternary expressions.
 *
 * e.g. `cond ? a : b` -> LUB(type(a), type(b))
 */
class TernaryExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val trueExpr = ReflectionAccess.getProperty(node, "trueExpression")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing trueExpression)",
            ).left()
        val falseExpr = ReflectionAccess.getProperty(node, "falseExpression")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing falseExpression)",
            ).left()

        val trueType = context.calculateType(trueExpr)
        val falseType = context.calculateType(falseExpr)

        return TypeLub.lub(trueType, falseType).right()
    }
}
