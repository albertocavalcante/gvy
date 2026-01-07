package com.github.albertocavalcante.gvy.semantics.calculator.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
 * Calculates types for map literal expressions.
 *
 * e.g. [a: 1, b: 2] -> java.util.LinkedHashMap<String, Integer>
 */
class MapExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? = calculateResult(node, context).getOrNull()

    override fun calculateResult(node: Any, context: TypeContext): TypeResult {
        val entries = getEntries(node)
            ?: return TypeInferenceError.UnsupportedNode(
                nodeType = node::class.simpleName ?: "unknown (no mapEntryExpressions property)",
            ).left()

        if (entries.isEmpty()) {
            return SemanticType.Known(
                "java.util.LinkedHashMap",
                listOf(TypeConstants.OBJECT, TypeConstants.OBJECT),
            ).right()
        }

        val keyTypes = mutableListOf<SemanticType>()
        val valueTypes = mutableListOf<SemanticType>()

        for (entry in entries) {
            val keyExpr = ReflectionAccess.getProperty(entry, "keyExpression")
            val valueExpr = ReflectionAccess.getProperty(entry, "valueExpression")

            if (keyExpr != null) {
                when (val result = context.calculateTypeResult(keyExpr)) {
                    is Either.Left -> return result
                    is Either.Right -> keyTypes.add(result.value)
                }
            }

            if (valueExpr != null) {
                when (val result = context.calculateTypeResult(valueExpr)) {
                    is Either.Left -> return result
                    is Either.Right -> valueTypes.add(result.value)
                }
            }
        }

        val keyLub = if (keyTypes.isNotEmpty()) TypeLub.lub(keyTypes) else TypeConstants.OBJECT
        val valueLub = if (valueTypes.isNotEmpty()) TypeLub.lub(valueTypes) else TypeConstants.OBJECT

        return SemanticType.Known("java.util.LinkedHashMap", listOf(keyLub, valueLub)).right()
    }

    private fun getEntries(node: Any): List<Any>? =
        ReflectionAccess.getListFromGetterOrField(node, "getMapEntryExpressions", "mapEntryExpressions")
}
