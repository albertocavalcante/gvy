// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull

private val JSON_NUMBER_LITERAL = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** Keeps exact numeric text when kotlinx otherwise serializes a JSON literal through Double. */
@OptIn(ExperimentalSerializationApi::class)
internal fun JsonElement.withExactNumberLiterals(): JsonElement = when (this) {
    JsonNull -> this
    is JsonPrimitive -> when {
        isString || booleanOrNull != null -> this
        // JsonUnquotedLiteral deliberately accepts arbitrary text, so validate before bypassing quoting.
        !JSON_NUMBER_LITERAL.matches(content) -> throw SerializationException("Invalid JSON number literal")
        else -> JsonUnquotedLiteral(content)
    }

    is JsonArray -> JsonArray(map { it.withExactNumberLiterals() })
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.withExactNumberLiterals() })
}
