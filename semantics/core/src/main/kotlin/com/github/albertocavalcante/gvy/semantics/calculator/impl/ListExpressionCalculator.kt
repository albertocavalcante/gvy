package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for list literal expressions.
 *
 * e.g. [1, 2, 3] -> java.util.ArrayList<Integer>
 */
class ListExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val expressions = getExpressions(node)
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = node::class.simpleName ?: "unknown (no expressions property)",
            ).left()

        if (expressions.isEmpty()) {
            return SemanticType.Known("java.util.ArrayList", listOf(TypeConstants.OBJECT)).right()
        }

        // Traverse pattern: collect all results, short-circuit on first error
        val elementTypes = mutableListOf<SemanticType>()
        for (expr in expressions) {
            when (val result = context.calculateTypeResult(expr)) {
                is Either.Left -> return result
                is Either.Right -> elementTypes.add(result.value)
            }
        }

        val lub = TypeLub.lub(elementTypes)
        return SemanticType.Known("java.util.ArrayList", listOf(lub)).right()
    }

    private fun getExpressions(node: Any): List<Any>? =
        ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")
}
