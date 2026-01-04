package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
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

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        // If the node doesn't have a 'value' property, return null (can't handle it)
        val hasValue = hasValueProperty(node)
        if (!hasValue) return null

        val value = getValue(node)

        // If value is null (null literal), type as Object
        if (value == null) return SemanticType.Known("java.lang.Object", emptyList())

        return when (value) {
            is Int -> SemanticType.Known("java.lang.Integer", emptyList())
            is String -> SemanticType.Known("java.lang.String", emptyList())
            is Boolean -> SemanticType.Known("java.lang.Boolean", emptyList())
            is BigDecimal -> SemanticType.Known("java.math.BigDecimal", emptyList())
            is BigInteger -> SemanticType.Known("java.math.BigInteger", emptyList())
            is Double -> SemanticType.Known("java.lang.Double", emptyList())
            is Float -> SemanticType.Known("java.lang.Float", emptyList())
            is Long -> SemanticType.Known("java.lang.Long", emptyList())
            is Short -> SemanticType.Known("java.lang.Short", emptyList())
            is Byte -> SemanticType.Known("java.lang.Byte", emptyList())
            is Char -> SemanticType.Known("java.lang.Character", emptyList())
            else -> SemanticType.Known(value.javaClass.name, emptyList())
        }
    }

    private fun hasValueProperty(node: Any): Boolean {
        // Check if node has either a getValue() method or a value field
        return runCatching { node::class.java.getMethod("getValue") }.isSuccess ||
            runCatching {
                val field = node::class.java.getDeclaredField("value")
                field.isAccessible = true
                field
            }.isSuccess
    }

    private fun getValue(node: Any): Any? {
        // Try to get 'value' property via reflection.
        return ReflectionAccess.invokeNoArg(node, "getValue") ?: ReflectionAccess.getField(node, "value")
    }
}
