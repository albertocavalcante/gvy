package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for declaration expressions.
 *
 * Note: Phase 2 does not model scope mutation; this calculator only returns the
 * type of the RHS expression.
 */
class DeclarationExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        if (node::class.java.simpleName != "DeclarationExpression") return null

        val right =
            ReflectionAccess.getProperty(node, "rightExpression")
                ?: ReflectionAccess.getProperty(node, "right")
                ?: return null

        return context.calculateType(right)
    }
}
