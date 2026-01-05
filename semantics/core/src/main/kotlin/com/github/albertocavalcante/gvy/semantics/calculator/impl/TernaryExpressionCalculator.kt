package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for ternary expressions.
 *
 * e.g. `cond ? a : b` -> LUB(type(a), type(b))
 */
class TernaryExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val trueExpr = ReflectionAccess.getProperty(node, "trueExpression") ?: return null
        val falseExpr = ReflectionAccess.getProperty(node, "falseExpression") ?: return null

        val trueType = context.calculateType(trueExpr)
        val falseType = context.calculateType(falseExpr)

        return TypeLub.lub(trueType, falseType)
    }
}
