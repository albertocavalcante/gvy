package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for Groovy GString expressions.
 */
class GStringExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        // Prefer a structural check to avoid relying on Groovy AST types.
        val hasStrings = ReflectionAccess.getProperty(node, "strings") is List<*>
        val hasValues = ReflectionAccess.getProperty(node, "values") is List<*>

        if (!hasStrings || !hasValues) return null

        return TypeConstants.GSTRING
    }
}
