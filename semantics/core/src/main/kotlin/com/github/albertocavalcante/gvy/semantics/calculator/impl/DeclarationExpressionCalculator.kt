package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.Either
import arrow.core.left
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for declaration expressions.
 *
 * Note: Phase 2 does not model scope mutation; this calculator only returns the
 * type of the RHS expression.
 */
class DeclarationExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        if (node::class.java.simpleName != "DeclarationExpression") {
            return TypeInferenceError.UnsupportedNode(
                nodeType = node::class.simpleName ?: "unknown",
            ).left()
        }

        val right = ReflectionAccess.getProperty(node, "rightExpression")
            ?: ReflectionAccess.getProperty(node, "right")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "DeclarationExpression (missing right expression)",
            ).left()

        return when (val result = context.calculateTypeResult(right)) {
            is Either.Left -> result
            is Either.Right -> result
        }
    }
}
