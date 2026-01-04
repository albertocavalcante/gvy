package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for map literal expressions.
 *
 * e.g. [a: 1, b: 2] -> java.util.LinkedHashMap<String, Integer>
 */
class MapExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        val entries = getEntries(node) ?: return null

        if (entries.isEmpty()) {
            return SemanticType.Known("java.util.LinkedHashMap", listOf(TypeConstants.OBJECT, TypeConstants.OBJECT))
        }

        val keyTypes = entries
            .mapNotNull { ReflectionAccess.getProperty(it, "keyExpression") }
            .map { context.calculateType(it) }

        val valueTypes = entries
            .mapNotNull { ReflectionAccess.getProperty(it, "valueExpression") }
            .map { context.calculateType(it) }

        val keyLub = if (keyTypes.isNotEmpty()) TypeLub.lub(keyTypes) else TypeConstants.OBJECT
        val valueLub = if (valueTypes.isNotEmpty()) TypeLub.lub(valueTypes) else TypeConstants.OBJECT

        return SemanticType.Known("java.util.LinkedHashMap", listOf(keyLub, valueLub))
    }

    private fun getEntries(node: Any): List<Any>? =
        ReflectionAccess.getListFromGetterOrField(node, "getMapEntryExpressions", "mapEntryExpressions")
}
