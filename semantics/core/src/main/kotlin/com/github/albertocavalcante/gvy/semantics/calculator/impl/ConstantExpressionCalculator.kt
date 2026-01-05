package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import java.math.BigDecimal
import java.math.BigInteger
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
            is ValuePropertyResult.Present ->
                result.value?.let(::typeForValue)
                    ?: SemanticType.Null
        }

    private fun typeForValue(value: Any): SemanticType = when (value) {
        is Boolean -> TypeConstants.BOOLEAN
        is Byte -> TypeConstants.BYTE
        is Char -> TypeConstants.CHAR
        is Short -> TypeConstants.SHORT
        is Int -> TypeConstants.INT
        is Long -> TypeConstants.LONG
        is Float -> TypeConstants.FLOAT
        is Double -> TypeConstants.DOUBLE
        is String -> TypeConstants.STRING
        is BigDecimal -> TypeConstants.BIG_DECIMAL
        is BigInteger -> TypeConstants.BIG_INTEGER
        else -> SemanticType.Known(value.javaClass.name, emptyList())
    }

    private sealed class ValuePropertyResult {
        object Missing : ValuePropertyResult()
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

        if (getter == null && field == null) return ValuePropertyResult.Missing

        val valueResult = runCatching {
            when {
                getter != null -> getter.invoke(node)
                field != null -> field.get(node)
                else -> null
            }
        }

        return if (valueResult.isFailure) {
            ValuePropertyResult.Missing
        } else {
            ValuePropertyResult.Present(valueResult.getOrNull())
        }
    }
}
