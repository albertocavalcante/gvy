// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal object RecoveryWireFrames {
    fun evidence(stream: WireStreamEvidence): JsonObject = buildJsonObject {
        put("prefixBase64", stream.prefixBase64)
        put("retainedBytes", stream.retainedBytes)
        put("transferredBytes", stream.transferredBytes)
        put("endOfInputObserved", stream.endOfInputObserved)
        put("inFlightOperations", stream.inFlightOperations)
        put("gaps", buildJsonArray { stream.gaps.forEach { add(JsonPrimitive(it.name)) } })
    }

    fun frames(stream: WireStreamEvidence): List<JsonObject> {
        require(stream.losslessSoFar && stream.inFlightOperations == 0) {
            "Wire evidence has gaps or active operations"
        }
        val bytes = Base64.getDecoder().decode(stream.prefixBase64)
        require(bytes.size == stream.retainedBytes) { "Wire evidence byte count differs from retained bytes" }
        val messages = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < bytes.size) {
            val headerEnd = (offset until bytes.size - 3).firstOrNull { index ->
                bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte() &&
                    bytes[index + 2] == 13.toByte() && bytes[index + 3] == 10.toByte()
            } ?: throw IllegalArgumentException("Incomplete wire header")
            val header = bytes.copyOfRange(offset, headerEnd).toString(Charsets.US_ASCII)
            val lengths = header.split("\r\n").mapNotNull { line ->
                val parts = line.split(':', limit = 2)
                if (parts.size == 2 && parts[0].equals("Content-Length", ignoreCase = true)) parts[1].trim() else null
            }
            require(lengths.size == 1 && lengths.single().matches(Regex("[0-9]+"))) { "Invalid Content-Length" }
            val length = lengths.single().toIntOrNull() ?: throw IllegalArgumentException("Invalid Content-Length")
            val start = headerEnd + 4
            require(length <= bytes.size - start) { "Incomplete wire body" }
            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT,
                ).decode(ByteBuffer.wrap(bytes, start, length)).toString()
            messages += Json.parseToJsonElement(body).jsonObject
            offset = start + length
        }
        return messages
    }
}
