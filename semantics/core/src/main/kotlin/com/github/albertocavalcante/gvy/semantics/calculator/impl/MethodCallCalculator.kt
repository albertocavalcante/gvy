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
        // TODO(#1585): Unqualified calls have no receiver node, so the receiver type stays Unknown and the
        //   result is an error. Resolving the implicit receiver (this/owner/delegate) is not implemented yet.
        //   See: https://github.com/albertocavalcante/gvy/issues/1585

        // Traverse pattern: collect all results, short-circuit on first error
        val argTypes = mutableListOf<SemanticType>()
        for (arg in arguments) {
            when (val result = context.calculateTypeResult(arg)) {
                is Either.Left -> return result
                is Either.Right -> argTypes.add(result.value)
            }
        }

        return context.getMethodReturnTypeResult(receiverType, methodName, argTypes)
    }

    private fun getMethodAsString(node: Any): String? =
        ReflectionAccess.invokeNoArg(node, "getMethodAsString") as? String

    private fun getArguments(node: Any): List<Any> {
        val arguments = ReflectionAccess.invokeNoArg(node, "getArguments")
            ?: ReflectionAccess.getField(node, "arguments")

        // A Groovy call's arguments are a TupleExpression (an Iterable of expressions, not a List).
        return when (arguments) {
            is Iterable<*> -> arguments.filterNotNull()
            else ->
                arguments
                    ?.let { ReflectionAccess.invokeNoArg(it, "getExpressions") as? List<*> }
                    ?.filterNotNull()
                    .orEmpty()
        }
    }
}
