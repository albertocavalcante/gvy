package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.syntax.Types

/**
 * Handles type inference for Groovy expressions.
 *
 * Inspired by IntelliJ's TypeInferenceHelper, this provides static type inference
 * for common Groovy patterns without requiring full semantic analysis.
 *
 * ## Supported Patterns
 * - List literals: `[1, 2, 3]` → `ArrayList<Integer>`
 * - Map literals: `[a: 1]` → `LinkedHashMap`
 * - Constructor calls: `new Person()` → `Person`
 * - Common method calls: `toString()` → `String`, `hashCode()` → `int`
 * - Binary expressions: `1 + 2` → `int`, `"a" + "b"` → `String`
 */
object TypeInferencer {

    // Type constants to avoid duplication
    private const val TYPE_STRING = "java.lang.String"
    private const val TYPE_OBJECT = "java.lang.Object"
    private const val TYPE_CLASS = "java.lang.Class"
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"

    private const val PRECEDENCE_UNKNOWN = 0
    private const val PRECEDENCE_SMALL_INT = 1
    private const val PRECEDENCE_INT = 2
    private const val PRECEDENCE_LONG = 3
    private const val PRECEDENCE_FLOAT = 4
    private const val PRECEDENCE_DOUBLE = 5
    private const val PRECEDENCE_BIG_INTEGER = 6
    private const val PRECEDENCE_BIG_DECIMAL = 7

    /**
     * Infer the type of a variable declaration.
     * Prefers explicit type annotations over inference.
     */
    fun inferType(declaration: DeclarationExpression): String {
        val variable = declaration.variableExpression
        val declaredType = variable.type

        // If declared type is specific (not dynamic/Object), use it
        if (!declaredType.isDynamicOrObject()) {
            return declaredType.name
        }

        // Otherwise, try to infer from initializer
        return declaration.rightExpression
            ?.let { inferExpressionType(it) }
            ?: TYPE_OBJECT
    }

    /**
     * Infer the type of an expression.
     * Uses pattern matching on expression types for clean dispatch.
     */
    fun inferExpressionType(expression: Expression): String = when (expression) {
        is ListExpression -> inferListType(expression)
        is MapExpression -> "java.util.LinkedHashMap"
        is ConstructorCallExpression -> inferConstructorType(expression)
        is MethodCallExpression -> inferMethodCallType(expression)
        is BinaryExpression -> inferBinaryExpressionType(expression)
        else -> expression.type.name
    }

    private fun inferConstructorType(expression: ConstructorCallExpression): String {
        val typeName = expression.type.name
        return when (typeName) {
            "ArrayList" -> "java.util.ArrayList"
            "LinkedList" -> "java.util.LinkedList"
            "HashMap" -> "java.util.HashMap"
            "LinkedHashMap" -> "java.util.LinkedHashMap"
            "HashSet" -> "java.util.HashSet"
            "TreeMap" -> "java.util.TreeMap"
            "StringBuilder" -> "java.lang.StringBuilder"
            else -> if (expression.type.redirect() != expression.type) {
                expression.type.redirect().name
            } else {
                typeName
            }
        }
    }

    // ==========================================================================
    // List Type Inference
    // ==========================================================================

    private fun inferListType(expression: ListExpression): String {
        val elements = expression.expressions
        if (elements.isEmpty()) {
            return "java.util.ArrayList"
        }

        // Vision: Implement Least Upper Bound (LUB) analysis like IntelliJ.
        // IntelliJ uses a sophisticated algorithm to find the most specific common supertype
        // (e.g. Serializable & Comparable) for mixed lists.
        //
        // Current Implementation: Basic homogeneity check.
        // 1. If all elements are the same type → ArrayList<Type>
        // 2. If mixed → ArrayList<java.lang.Object>

        val firstType = inferExpressionType(elements[0])
        val allSame = elements.asSequence()
            .drop(1)
            .all { inferExpressionType(it) == firstType }

        return if (allSame) {
            val boxedType = boxType(firstType)
            "java.util.ArrayList<$boxedType>"
        } else {
            "java.util.ArrayList<java.lang.Object>"
        }
    }

    // ==========================================================================
    // Method Call Type Inference
    // ==========================================================================

    /**
     * Infer return type from common method calls.
     * For well-known methods on Object, we can infer types without full resolution.
     */
    private fun inferMethodCallType(call: MethodCallExpression): String = when (call.methodAsString) {
        "toString" -> TYPE_STRING
        "hashCode" -> TYPE_INT
        "getClass" -> TYPE_CLASS
        "equals" -> TYPE_BOOLEAN
        "clone" -> call.objectExpression?.type?.name ?: TYPE_OBJECT
        else -> call.type.name
    }

    // ==========================================================================
    // Binary Expression Type Inference
    // ==========================================================================

    /**
     * Infer type from binary expressions using Groovy's type promotion rules.
     *
     * String concatenation always produces String.
     * Numeric operations follow Java-like type promotion.
     */
    private fun inferBinaryExpressionType(expr: BinaryExpression): String {
        val leftType = inferExpressionType(expr.leftExpression)
        val rightType = inferExpressionType(expr.rightExpression)
        val operation = expr.operation.type

        return when {
            // String concatenation always produces String
            isStringConcatenation(leftType, rightType, operation) -> TYPE_STRING

            // Comparison operators always produce boolean
            isComparisonOperation(operation) -> TYPE_BOOLEAN

            // Numeric operations use type promotion
            isNumericOperation(operation) -> promoteNumericTypes(leftType, rightType)

            // Assignment returns the right-hand type
            operation == Types.ASSIGN -> rightType

            // Fallback to expression's declared type
            else -> expr.type.name
        }
    }

    private fun isStringConcatenation(leftType: String, rightType: String, operation: Int): Boolean =
        operation == Types.PLUS && (leftType == TYPE_STRING || rightType == TYPE_STRING)

    private fun isComparisonOperation(operation: Int): Boolean = operation in listOf(
        Types.COMPARE_EQUAL,
        Types.COMPARE_NOT_EQUAL,
        Types.COMPARE_LESS_THAN,
        Types.COMPARE_LESS_THAN_EQUAL,
        Types.COMPARE_GREATER_THAN,
        Types.COMPARE_GREATER_THAN_EQUAL,
        Types.COMPARE_IDENTICAL,
        Types.COMPARE_NOT_IDENTICAL,
    )

    private fun isNumericOperation(operation: Int): Boolean = operation in listOf(
        Types.PLUS,
        Types.MINUS,
        Types.MULTIPLY,
        Types.DIVIDE,
        Types.MOD,
        Types.POWER,
        Types.INTDIV,
    )

    /**
     * Promote numeric types following Java/Groovy rules.
     * BigDecimal is Groovy's default for floating-point literals.
     *
     * Key semantics:
     * - byte + short → int (all small integer operations promote to int)
     * - Non-numeric operands → Object (Groovy's operator overloading is complex)
     */
    private fun promoteNumericTypes(leftType: String, rightType: String): String {
        val leftPrecedence = numericPrecedence(leftType)
        val rightPrecedence = numericPrecedence(rightType)

        // If either operand is not a known numeric type, we cannot safely promote.
        // A safe fallback is Object, as Groovy's operator overloading is complex.
        if (leftPrecedence == PRECEDENCE_UNKNOWN || rightPrecedence == PRECEDENCE_UNKNOWN) {
            return TYPE_OBJECT
        }

        val resultPrecedence = maxOf(leftPrecedence, rightPrecedence)

        // Promote based on the highest precedence, with special handling for small integer types.
        return when {
            resultPrecedence >= PRECEDENCE_BIG_DECIMAL -> "java.math.BigDecimal"
            resultPrecedence == PRECEDENCE_BIG_INTEGER -> "java.math.BigInteger"
            resultPrecedence == PRECEDENCE_DOUBLE -> "double"
            resultPrecedence == PRECEDENCE_FLOAT -> "float"
            resultPrecedence == PRECEDENCE_LONG -> "long"
            else -> "int" // byte, short, and int operations result in int
        }
    }

    /**
     * Numeric type precedence for promotion rules.
     * Higher value = higher precedence in numeric operations.
     */
    private fun numericPrecedence(type: String): Int = when (type) {
        "java.math.BigDecimal", "BigDecimal" -> PRECEDENCE_BIG_DECIMAL
        "java.math.BigInteger", "BigInteger" -> PRECEDENCE_BIG_INTEGER
        "double", "java.lang.Double" -> PRECEDENCE_DOUBLE
        "float", "java.lang.Float" -> PRECEDENCE_FLOAT
        "long", "java.lang.Long" -> PRECEDENCE_LONG
        "int", "java.lang.Integer", "Integer" -> PRECEDENCE_INT
        "short", "java.lang.Short" -> PRECEDENCE_SMALL_INT
        "byte", "java.lang.Byte" -> PRECEDENCE_SMALL_INT
        else -> PRECEDENCE_UNKNOWN
    }

    // ==========================================================================
    // Utility Functions
    // ==========================================================================

    private fun boxType(type: String): String = when (type) {
        "int" -> "java.lang.Integer"
        "boolean" -> "java.lang.Boolean"
        "char" -> "java.lang.Character"
        "byte" -> "java.lang.Byte"
        "short" -> "java.lang.Short"
        "long" -> "java.lang.Long"
        "float" -> "java.lang.Float"
        "double" -> "java.lang.Double"
        else -> type
    }
}
