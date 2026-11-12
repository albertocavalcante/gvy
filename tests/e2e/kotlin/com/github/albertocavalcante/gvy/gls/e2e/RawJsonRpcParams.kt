// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlinx.serialization.json.JsonElement
import com.google.gson.JsonElement as GsonJsonElement

private val rawJsonValidator = GsonBuilder().setStrictness(Strictness.STRICT).create()

/** Validates and snapshots authored protocol parameters before SDK dispatch can hide serialization failures. */
internal data class RawJsonRpcParams(val json: JsonElement) {
    val encodedJson: String = json.toString().also { text ->
        // Validate without re-encoding: encoding a JSON tree can normalize authored numeric literals.
        rawJsonValidator.fromJson(text, GsonJsonElement::class.java)
    }
}

/** Registration affects only the raw carrier; typed LSP objects retain the launcher's ordinary adapters. */
internal fun GsonBuilder.registerRawJsonRpcParams(): GsonBuilder =
    registerTypeAdapter(RawJsonRpcParams::class.java, RawJsonRpcParamsAdapter())

private class RawJsonRpcParamsAdapter : TypeAdapter<RawJsonRpcParams>() {
    override fun write(writer: JsonWriter, value: RawJsonRpcParams?) {
        val originalPolicy = writer.serializeNulls
        try {
            writer.serializeNulls = true
            writer.jsonValue(value?.encodedJson ?: "null")
        } finally {
            writer.serializeNulls = originalPolicy
        }
    }

    override fun read(reader: JsonReader): RawJsonRpcParams =
        throw UnsupportedOperationException("Raw JSON-RPC parameters are outbound-only")
}
