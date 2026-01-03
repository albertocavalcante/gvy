package com.github.albertocavalcante.groovylsp.indexing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Utility for parsing NDJSON (Newline-Delimited JSON) content.
 * Used for reading LSIF dump files which use NDJSON format.
 */
object NdjsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses NDJSON content into a list of JsonObject.
     * Empty lines are filtered out.
     */
    fun parse(content: String): List<JsonObject> = content.lines()
        .filter { it.isNotBlank() }
        .map { line -> json.parseToJsonElement(line).jsonObject }

    /**
     * Parses NDJSON content into a list of maps for easier test assertions.
     * Nested objects are converted to Map<String, Any>.
     * Arrays are converted to List<Any>.
     */
    fun parseToMaps(content: String): List<Map<String, Any?>> = parse(content).map { jsonObj -> toMap(jsonObj) }

    private fun toMap(jsonObject: JsonObject): Map<String, Any?> = jsonObject.entries.associate { (key, value) ->
        key to toValue(value)
    }

    private fun toValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> toMap(element)
        is JsonArray -> element.map { toValue(it) }
        is JsonPrimitive -> {
            when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.intOrNull != null -> element.intOrNull
                element.longOrNull != null -> element.longOrNull
                element.doubleOrNull != null -> element.doubleOrNull
                else -> element.content
            }
        }
    }
}
