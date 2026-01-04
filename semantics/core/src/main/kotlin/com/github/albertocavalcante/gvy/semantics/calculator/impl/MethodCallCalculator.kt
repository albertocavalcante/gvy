package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates return types for method calls.
 *
 * Uses context.getMethodReturnType() to resolve.
 */
class MethodCallCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val receiver = ReflectionAccess.getProperty(node, "receiver")
        // Test doubles may expose method name as a plain String property; Groovy AST uses getMethodAsString().
        val methodName = ReflectionAccess.getStringProperty(node, "methodName")
            ?: getMethodAsString(node) // Groovy AST
            ?: return null

        val arguments = getArguments(node)

        val receiverType =
            if (receiver != null) context.calculateType(receiver) else SemanticType.Unknown("Implicit receiver")
        // TODO(#638): Support implicit receiver resolution (this/owner/delegate) via TypeContext.
        //   See: https://github.com/albertocavalcante/gvy/issues/638

        val argTypes = arguments.map { context.calculateType(it) }

        return context.getMethodReturnType(receiverType, methodName, argTypes)
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
