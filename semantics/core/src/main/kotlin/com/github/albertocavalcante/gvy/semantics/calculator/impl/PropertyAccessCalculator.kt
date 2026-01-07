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
 * Calculates types for property access expressions.
 *
 * e.g. obj.prop -> queries context.getFieldType
 */
class PropertyAccessCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val receiver =
            ReflectionAccess.getProperty(node, "objectExpression") ?: ReflectionAccess.getProperty(node, "receiver")
        val property =
            ReflectionAccess.getProperty(node, "property")
                ?: ReflectionAccess.getProperty(node, "propertyExpression")
                ?: return TypeInferenceError.UnsupportedNode(
                    nodeType = "${node::class.simpleName ?: "unknown"} (missing property)",
                ).left()

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

        val propertyName = getPropertyName(property)
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing property name)",
            ).left()

        return when (val result = context.getFieldTypeResult(receiverType, propertyName)) {
            is Either.Left -> result
            is Either.Right -> result
        }
    }

    private fun getPropertyName(property: Any): String? {
        // ConstantExpression(value="length") -> "length"
        val fromGetterOrField = ReflectionAccess.getStringFromGetterOrField(property, "getValue", "value")

        // Test doubles may model the property name directly as a String.
        return fromGetterOrField ?: (property as? String)
    }
}
