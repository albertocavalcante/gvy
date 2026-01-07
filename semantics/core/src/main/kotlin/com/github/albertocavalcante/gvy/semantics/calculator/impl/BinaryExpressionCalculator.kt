package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for binary expressions.
 *
 * E.g. a + b, a == b, etc.
 * Uses reflection to access leftExpression, rightExpression, and operation.
 */
class BinaryExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val left = ReflectionAccess.getProperty(node, "leftExpression")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing leftExpression)",
            ).left()
        val right = ReflectionAccess.getProperty(node, "rightExpression")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing rightExpression)",
            ).left()
        val operation = ReflectionAccess.getProperty(node, "operation")
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing operation)",
            ).left()
        val opText = getOperationText(operation)
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing operation text)",
            ).left()

        val leftType = when (val result = context.calculateTypeResult(left)) {
            is Either.Left -> return result
            is Either.Right -> result.value
        }
        val rightType = when (val result = context.calculateTypeResult(right)) {
            is Either.Left -> return result
            is Either.Right -> result.value
        }

        return when (opText) {
            // Boolean result operators
            "==", "!=", "<", ">", "<=", ">=", "&&", "||" -> SemanticType.Primitive(PrimitiveKind.BOOLEAN).right()

            // Groovy boolean operators
            "=~", "==~", "in" -> SemanticType.Primitive(PrimitiveKind.BOOLEAN).right()

            // Groovy comparison
            "<=>" -> TypeConstants.INT.right()

            // Arithmetic operators
            "+", "-", "*", "/", "%" -> calculateArithmetic(leftType, rightType, opText).right()

            // Groovy power
            "**" -> TypeLub.lub(leftType, rightType).right()

            // Assignment (not usually a type calculation subject but expression has type of RHS)
            "=" -> rightType.right()

            else -> TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (unsupported operation: $opText)",
            ).left()
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
