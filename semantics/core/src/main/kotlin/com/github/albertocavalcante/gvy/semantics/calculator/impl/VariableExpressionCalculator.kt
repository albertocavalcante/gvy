package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for variable expressions.
 *
 * e.g. `x` -> lookup in TypeContext
 */
class VariableExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val name = ReflectionAccess.getStringProperty(node, "name")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing name property)",
            ).left()

        return when (val result = context.lookupSymbolResult(name)) {
            is Either.Left -> result
            is Either.Right -> result.value.right()
        }
    }
}
