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
 * Calculates return types for method calls.
 *
 * Uses context.getMethodReturnType() to resolve.
 */
class MethodCallCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val receiver = ReflectionAccess.getProperty(node, "receiver")
        // Test doubles may expose method name as a plain String property; Groovy AST uses getMethodAsString().
        val methodName = ReflectionAccess.getStringProperty(node, "methodName")
            ?: getMethodAsString(node) // Groovy AST
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing methodName)",
            ).left()

        val arguments = getArguments(node)

        val receiverType = if (receiver != null) {
            when (val result = context.calculateTypeResult(receiver)) {
                is Either.Left -> return result
                is Either.Right -> result.value
            }
        } else {
            SemanticType.Unknown("Implicit receiver")
        }
        // TODO(#638): Support implicit receiver resolution (this/owner/delegate) via TypeContext.
        //   See: https://github.com/albertocavalcante/gvy/issues/638

        // Traverse pattern: collect all results, short-circuit on first error
        val argTypes = mutableListOf<SemanticType>()
        for (arg in arguments) {
            when (val result = context.calculateTypeResult(arg)) {
                is Either.Left -> return result
                is Either.Right -> argTypes.add(result.value)
            }
        }

        return when (val result = context.getMethodReturnTypeResult(receiverType, methodName, argTypes)) {
            is Either.Left -> result
            is Either.Right -> result
        }
    }

    private fun getMethodAsString(node: Any): String? =
        ReflectionAccess.invokeNoArg(node, "getMethodAsString") as? String

    private fun getArguments(node: Any): List<Any> {
        val arguments = ReflectionAccess.invokeNoArg(node, "getArguments")
            ?: ReflectionAccess.getField(node, "arguments")

        return when (arguments) {
            is List<*> -> arguments.filterNotNull()
            else -> emptyList()
        }
    }
}
