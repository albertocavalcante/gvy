package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import kotlin.reflect.KClass

/**
 * Calculates types for Groovy GString expressions.
 */
class GStringExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        // Prefer a structural check to avoid relying on Groovy AST types.
        val hasStrings = ReflectionAccess.getProperty(node, "strings") is List<*>
        val hasValues = ReflectionAccess.getProperty(node, "values") is List<*>

        return if (!hasStrings || !hasValues) {
            TypeInferenceError.UnsupportedNode(
                nodeType = "${node::class.simpleName ?: "unknown"} (missing strings/values properties)",
            ).left()
        } else {
            TypeConstants.GSTRING.right()
        }
    }
}
