// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.transport.lsp4j

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import com.google.gson.JsonObject as LspJsonObject

/** Converts protocol settings into values understood by the existing configuration parser. */
internal fun configurationPayload(payload: Any?): Map<String, Any>? = when (payload) {
    is JsonObject -> payload.toConfigurationMap()
    is Map<*, *> -> payload.entries.mapNotNull { (key, value) ->
        if (key is String && value != null) key to value else null
    }.toMap()
    is LspJsonObject -> parseLspConfiguration(payload)
    else -> null
}

// TODO(#1704): Remove the LSP4J adapter when the protocol transport migrates to LSP4K.
//   See: https://github.com/albertocavalcante/gvy/issues/1704
private fun parseLspConfiguration(payload: LspJsonObject): Map<String, Any>? = try {
    (Json.parseToJsonElement(payload.toString()) as? JsonObject)?.toConfigurationMap()
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.toConfigurationMap(): Map<String, Any> =
    entries.mapNotNull { (key, value) -> value.toConfigurationValue()?.let { key to it } }.toMap()

private fun JsonElement.toConfigurationValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> entries.associate { (key, value) -> key to value.toConfigurationValue() }
    is JsonArray -> map { it.toConfigurationValue() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        else -> doubleOrNull
    }
}
