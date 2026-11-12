package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.e2e.JsonBridge.toJavaObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

/** Exact mathematical bounds are distinct from structural JSON equality and third-party JsonPath filters. */
internal object JsonNumberComparison {
    fun isAtLeast(actual: JsonElement?, expected: JsonElement?): Boolean =
        comparison(actual, expected)?.let { it >= 0 } ?: false

    fun isAtMost(actual: JsonElement?, expected: JsonElement?): Boolean =
        comparison(actual, expected)?.let { it <= 0 } ?: false

    private fun comparison(actual: JsonElement?, expected: JsonElement?): Int? {
        val actualNum = actual.numericPrimitive() ?: return null
        val expectedNum = expected.numericPrimitive() ?: return null
        return actualNum.comparableNumber().compareTo(expectedNum.comparableNumber())
    }

    private fun JsonElement?.numericPrimitive(): JsonPrimitive? =
        (this as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull || it.booleanOrNull != null }

    private fun JsonPrimitive.comparableNumber(): BigDecimal {
        content.toBigDecimalOrNull()?.let { return it }
        toJavaObject() // Validate syntax before reporting a valid number as outside the comparison domain.
        // TODO(#1700): Support larger exponents without expanding their numeric value.
        //   See: https://github.com/albertocavalcante/gvy/issues/1700
        throw UnsupportedOperationException("Numeric comparison exceeds the supported BigDecimal scale: $content")
    }
}
