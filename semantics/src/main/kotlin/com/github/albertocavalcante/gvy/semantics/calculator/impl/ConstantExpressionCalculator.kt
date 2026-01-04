package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
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

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        // If the node doesn't have a 'value' property, return null (can't handle it)
        val hasValue = hasValueProperty(node)
        if (!hasValue) return null

        val value = getValue(node)

        // If value is null (null literal), type as Object
        if (value == null) return SemanticType.Known("java.lang.Object", emptyList())

        return typeForValue(value)
    }

    private fun typeForValue(value: Any): SemanticType = SemanticType.Known(value.javaClass.name, emptyList())

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
