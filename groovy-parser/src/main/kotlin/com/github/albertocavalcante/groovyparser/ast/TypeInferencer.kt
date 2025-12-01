package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression

/**
 * Handles type inference for Groovy expressions.
 * Inspired by IntelliJ's TypeInferenceHelper.
 */
object TypeInferencer {

    /**
     * Infer the type of a variable declaration.
     */
    fun inferType(declaration: DeclarationExpression): String {
        val variable = declaration.variableExpression
        val declaredType = variable.type

        // If declared type is specific (not dynamic/Object), use it
        if (!ClassHelper.isDynamicTyped(declaredType) && declaredType != ClassHelper.OBJECT_TYPE) {
            return declaredType.name
        }

        // Otherwise, try to infer from initializer
        val initializer = declaration.rightExpression
        if (initializer != null) {
            return inferExpressionType(initializer)
        }

        return "java.lang.Object"
    }

    /**
     * Infer the type of an expression.
     */
    fun inferExpressionType(expression: Expression): String {
        return when (expression) {
            is ListExpression -> {
                val elements = expression.expressions
                if (elements.isEmpty()) {
                    return "java.util.ArrayList"
                }

                // Vision: Implement Least Upper Bound (LUB) analysis like IntelliJ.
                // IntelliJ uses a sophisticated algorithm to find the most specific common supertype
                // (e.g. Serializable & Comparable) for mixed lists.
                //
                // Current Implementation: Basic homogeneity check.
                // 1. If all elements are the same type -> ArrayList<Type>
                // 2. If mixed -> ArrayList<java.lang.Object>

                val firstType = inferExpressionType(elements[0])
                // Optimization: Skip the first element as we already inferred it
                val allSame = elements.asSequence().drop(1).all { inferExpressionType(it) == firstType }

                if (allSame) {
                    val boxedType = boxType(firstType)
                    "java.util.ArrayList<$boxedType>"
                } else {
                    "java.util.ArrayList<java.lang.Object>"
                }
            }

            is MapExpression -> "java.util.LinkedHashMap"
            else -> expression.type.name
        }
    }

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
