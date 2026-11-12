// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.transport.lsp4j

import com.github.albertocavalcante.gvy.gls.services.StatusNotification
import com.github.albertocavalcante.gvy.gls.services.StatusNotificationCodec
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/** Bridges the Kotlin status wire format to LSP4J's Gson transport. */
private class StatusNotificationAdapter :
    JsonSerializer<StatusNotification>,
    JsonDeserializer<StatusNotification> {
    override fun serialize(src: StatusNotification, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonParser.parseString(StatusNotificationCodec.encode(src))

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): StatusNotification = StatusNotificationCodec.decode(json.toString())
}

// TODO(#1704): Migrate LSP4J to LSP4K once protocol and transport parity are validated, retiring this bridge.
//   See: https://github.com/albertocavalcante/gvy/issues/1704
fun registerStatusNotificationAdapter(builder: GsonBuilder) {
    builder.registerTypeAdapter(StatusNotification::class.java, StatusNotificationAdapter())
}
