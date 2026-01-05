package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for list literal expressions.
 *
 * e.g. [1, 2, 3] -> java.util.ArrayList<Integer>
 */
class ListExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val expressions = getExpressions(node) ?: return null

        if (expressions.isEmpty()) {
            return SemanticType.Known("java.util.ArrayList", listOf(TypeConstants.OBJECT))
        }

        val elementTypes = expressions.map { context.calculateType(it) }
        val lub = TypeLub.lub(elementTypes)

        return SemanticType.Known("java.util.ArrayList", listOf(lub))
    }

    private fun getExpressions(node: Any): List<Any>? =
        ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")
}
