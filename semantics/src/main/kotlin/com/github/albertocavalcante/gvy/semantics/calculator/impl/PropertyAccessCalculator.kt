package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for property access expressions.
 *
 * e.g. obj.prop -> queries context.getFieldType
 */
class PropertyAccessCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val receiver =
            ReflectionAccess.getProperty(node, "objectExpression") ?: ReflectionAccess.getProperty(node, "receiver")
        val property =
            ReflectionAccess.getProperty(node, "property")
                ?: ReflectionAccess.getProperty(node, "propertyExpression")
                ?: return null

        val receiverType =
            if (receiver != null) context.calculateType(receiver) else SemanticType.Unknown("Implicit receiver")

        val propertyName = getPropertyName(property) ?: return null

        return context.getFieldType(receiverType, propertyName)
    }

    private fun getPropertyName(property: Any): String? {
        // ConstantExpression(value="length") -> "length"
        val fromGetterOrField = ReflectionAccess.getStringFromGetterOrField(property, "getValue", "value")
        return fromGetterOrField ?: (property as? String)
    }
}
