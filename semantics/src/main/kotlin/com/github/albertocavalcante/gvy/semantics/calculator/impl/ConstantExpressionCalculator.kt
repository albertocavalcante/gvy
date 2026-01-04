package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for constant/literal expressions.
 *
 * Works with any node that has a 'value' property (like ConstantExpression).
 */
class ConstantExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? =
        when (val result = readValueProperty(node)) {
            ValuePropertyResult.Missing -> null
            ValuePropertyResult.Unreadable -> null
            is ValuePropertyResult.Present ->
                result.value?.let(::typeForValue)
                    ?: SemanticType.Known("java.lang.Object", emptyList())
        }

    private fun typeForValue(value: Any): SemanticType = SemanticType.Known(value.javaClass.name, emptyList())

    private sealed class ValuePropertyResult {
        object Missing : ValuePropertyResult()
        object Unreadable : ValuePropertyResult()
        data class Present(val value: Any?) : ValuePropertyResult()
    }

    private fun readValueProperty(node: Any): ValuePropertyResult {
        // Distinguish:
        // - No value property (cannot handle) vs
        // - Value property exists but is null (null literal)
        //
        // We intentionally avoid ReflectionAccess here because it collapses
        // "missing property" and "null value" into the same `null` result.

        val getter = runCatching { node::class.java.getMethod("getValue") }.getOrNull()
        val field =
            if (getter != null) {
                null
            } else {
                runCatching {
                    node::class.java.getDeclaredField("value").apply { isAccessible = true }
                }.getOrNull()
            }

        val valueResult: Result<Any?>? = when {
            getter != null -> runCatching { getter.invoke(node) }
            field != null -> runCatching { field.get(node) }
            else -> null
        }

        return when {
            getter == null && field == null -> ValuePropertyResult.Missing
            valueResult == null -> ValuePropertyResult.Missing
            valueResult.isFailure -> ValuePropertyResult.Unreadable
            else -> ValuePropertyResult.Present(valueResult.getOrNull())
        }
    }
}
