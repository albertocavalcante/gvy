package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for binary expressions.
 *
 * E.g. a + b, a == b, etc.
 * Uses reflection to access leftExpression, rightExpression, and operation.
 */
class BinaryExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val left = ReflectionAccess.getProperty(node, "leftExpression") ?: return null
        val right = ReflectionAccess.getProperty(node, "rightExpression") ?: return null
        val operation = ReflectionAccess.getProperty(node, "operation") ?: return null
        val opText = getOperationText(operation) ?: return null

        val leftType = context.calculateType(left)
        val rightType = context.calculateType(right)

        return when (opText) {
            // Boolean result operators
            "==", "!=", "<", ">", "<=", ">=", "&&", "||" -> SemanticType.Primitive(PrimitiveKind.BOOLEAN)

            // Arithmetic operators
            "+", "-", "*", "/", "%" -> calculateArithmetic(leftType, rightType, opText)

            // Assignment (not usually a type calculation subject but expression has type of RHS)
            "=" -> rightType

            else -> null
        }
    }

    private fun calculateArithmetic(left: SemanticType, right: SemanticType, op: String): SemanticType {
        // String concatenation
        if (op == "+") {
            if (isString(left) || isString(right)) {
                return TypeConstants.STRING
            }
        }

        // Numeric promotion
        return TypeLub.lub(left, right)
    }

    private fun isString(type: SemanticType): Boolean =
        (type is SemanticType.Known && type.fqn == "java.lang.String") ||
            (type is SemanticType.Known && type.fqn == "groovy.lang.GString")

    private fun getOperationText(token: Any): String? {
        // Groovy Token has getText()
        val fromGetter = ReflectionAccess.invokeNoArg(token, "getText") as? String
        if (fromGetter != null) return fromGetter

        // Test double might handle property access
        return ReflectionAccess.getStringProperty(token, "text")
    }
}
