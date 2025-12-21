package com.github.albertocavalcante.groovylsp.e2e

import com.google.gson.Gson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import com.google.gson.JsonElement as GsonJsonElement

/**
 * Bridge utilities for converting between kotlinx.serialization.json.JsonElement
 * and Java types (for JsonPath interop) and Gson types (for LSP4J interop).
 *
 * This consolidates JSON conversion logic used across the E2E test harness.
 */
object JsonBridge {
    private val gson = Gson()

    /**
     * Converts a kotlinx JsonElement to a Java object suitable for JsonPath queries.
     * - JsonNull → null
     * - JsonPrimitive → String, Boolean, Long, Double (based on content)
     * - JsonArray → List<Any?>
     * - JsonObject → Map<String, Any?>
     */
    fun JsonElement.toJavaObject(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> {
            if (isString) {
                content
            } else {
                booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
            }
        }
        is JsonArray -> map { it.toJavaObject() }
        is JsonObject -> mapValues { it.value.toJavaObject() }
    }

    /**
     * Wraps a Java object back into a kotlinx JsonElement.
     * Handles primitives, collections, and POJOs (via Gson fallback for LSP4J types).
     */
    fun wrapJavaObject(obj: Any?): JsonElement = when (obj) {
        null -> JsonNull
        is String -> JsonPrimitive(obj)
        is Number -> JsonPrimitive(obj)
        is Boolean -> JsonPrimitive(obj)
        is List<*> -> JsonArray(obj.map { wrapJavaObject(it) })
        is Map<*, *> -> JsonObject(obj.entries.associate { (k, v) -> k.toString() to wrapJavaObject(v) })
        // For POJOs (like LSP4J types), use Gson to serialize then convert
        else -> {
            try {
                gson.toJsonTree(obj).toKotlinxJsonElement()
            } catch (e: Exception) {
                JsonPrimitive(obj.toString())
            }
        }
    }

    /**
     * Converts a Gson JsonElement to a kotlinx.serialization JsonElement.
     * Useful for bridging LSP4J responses to our test harness.
     */
    fun GsonJsonElement.toKotlinxJsonElement(): JsonElement = when {
        isJsonNull -> JsonNull
        isJsonPrimitive -> {
            val p = asJsonPrimitive
            when {
                p.isBoolean -> JsonPrimitive(p.asBoolean)
                p.isNumber -> JsonPrimitive(p.asNumber)
                else -> JsonPrimitive(p.asString)
            }
        }
        isJsonArray -> JsonArray(asJsonArray.map { it.toKotlinxJsonElement() })
        isJsonObject -> JsonObject(asJsonObject.entrySet().associate { it.key to it.value.toKotlinxJsonElement() })
        else -> JsonNull
    }

    /**
     * Extension to convert any object to kotlinx JsonElement using Gson as intermediary.
     * Particularly useful for LSP4J response objects.
     */
    fun Gson.toJsonElement(obj: Any?): JsonElement = toJsonTree(obj).toKotlinxJsonElement()

    /**
     * Converts any object to kotlinx JsonElement using the internal Gson instance.
     * This is the preferred method for converting LSP4J objects to JsonElement.
     */
    fun toJsonElement(obj: Any?): JsonElement {
        if (obj == null) return JsonNull
        return gson.toJsonTree(obj).toKotlinxJsonElement()
    }
}
