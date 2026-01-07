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
 * Calculates types for Elvis operator expressions.
 *
 * e.g. `a ?: b` -> LUB(type(a), type(b))
 */
class ElvisOperatorExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val left =
            ReflectionAccess.getProperty(node, "trueExpression")
                ?: ReflectionAccess.getProperty(node, "booleanExpression")
                ?: return TypeInferenceError.UnsupportedNode(
                    nodeType = "${node::class.simpleName ?: "unknown"} (missing trueExpression/booleanExpression)",
                ).left()

        val right = ReflectionAccess.getProperty(node, "falseExpression")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing falseExpression)",
            ).left()

        return TypeLub.lub(context.calculateType(left), context.calculateType(right)).right()
    }
}
