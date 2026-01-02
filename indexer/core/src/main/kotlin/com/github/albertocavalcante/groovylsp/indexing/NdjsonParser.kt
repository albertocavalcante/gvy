package com.github.albertocavalcante.groovylsp.indexing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
     */
    fun parseToMaps(content: String): List<Map<String, Any>> = parse(content).map { jsonObj -> toMap(jsonObj) }

    private fun toMap(jsonObject: JsonObject): Map<String, Any> = jsonObject.entries.associate { (key, value) ->
        key to toValue(value)
    }

    private fun toValue(element: JsonElement): Any = when {
        element is JsonObject -> toMap(element)
        element.jsonPrimitive.isString -> element.jsonPrimitive.content
        else -> try {
            element.jsonPrimitive.int
        } catch (_: NumberFormatException) {
            element.jsonPrimitive.content
        }
    }
}
