package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for Elvis operator expressions.
 *
 * e.g. `a ?: b` -> LUB(type(a), type(b))
 */
class ElvisOperatorExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val left =
            ReflectionAccess.getProperty(node, "trueExpression")
                ?: ReflectionAccess.getProperty(node, "booleanExpression")
                ?: return null

        val right = ReflectionAccess.getProperty(node, "falseExpression") ?: return null

        return TypeLub.lub(context.calculateType(left), context.calculateType(right))
    }
}
