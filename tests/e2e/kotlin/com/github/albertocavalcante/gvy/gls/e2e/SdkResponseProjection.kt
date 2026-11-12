// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** A convenience view of a decoded SDK response, independent of raw wire evidence. */
object SdkResponseProjection {
    fun project(response: Any?): JsonElement {
        val decoded = JsonBridge.wrapJavaObject(response)
        if (response !is Either<*, *> || decoded !is JsonObject) return decoded

        val branch = when {
            response.isLeft -> "left"
            response.isRight -> "right"
            else -> return decoded
        }
        val value = decoded[branch]
        if (value == null || value is JsonNull) return decoded

        return if (value is JsonArray) {
            buildJsonObject {
                put("dataOrigin", branch)
                put("items", value)
            }
        } else {
            value
        }
    }
}
