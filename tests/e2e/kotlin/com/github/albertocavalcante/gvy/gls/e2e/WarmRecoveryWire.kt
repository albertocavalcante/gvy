// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal data class WarmRecoveryRequest(
    val source: ColdRecoverySource,
    val method: String,
    val params: JsonObject,
    val result: JsonElement,
    val incarnation: Int = 0,
)

/** Binds the executed, single-open full-text history to independently captured wire frames. */
internal object WarmRecoveryWire {
    private val featureMethods = setOf(
        "textDocument/semanticTokens/full",
        "textDocument/hover",
        "textDocument/documentSymbol",
        "textDocument/completion",
        "groovy/awaitDiagnostics",
    )

    private val setupMethods = setOf("initialize", "groovy/runtimeFacts", "shutdown")

    fun bind(
        snapshot: WireEvidenceSnapshot,
        authoredSources: List<ColdRecoverySource>,
        requests: List<WarmRecoveryRequest>,
    ): JsonObject {
        require(authoredSources.isNotEmpty() && requests.isNotEmpty()) { "Warm replay requires sources and requests" }
        require(requests.all { it.incarnation == 0 }) { "Reopened documents are not supported" }
        val uri = authoredSources.first().uri
        require(authoredSources.all { it.uri == uri }) { "Warm sources must share one URI" }
        require(authoredSources.zipWithNext().all { (a, b) -> b.version > a.version }) {
            "Warm source versions must increase"
        }
        require(snapshot.received.endOfInputObserved) { "Server output EOF was not observed" }
        val sent = RecoveryWireFrames.frames(snapshot.transmitted)
        val received = RecoveryWireFrames.frames(snapshot.received)
        val bindings = mutableListOf<JsonObject>()
        var stateIndex = -1
        var closed = false
        var nextRequest = 0
        for ((index, message) in sent.withIndex()) {
            val method = (message["method"] as? JsonPrimitive)?.content
            when (method) {
                "textDocument/didOpen" -> {
                    require(stateIndex == -1 && !closed) { "Warm replay supports one document open" }
                    val doc = document(message)
                    val source = authoredSources.first()
                    require(
                        doc != null &&
                            doc["uri"] == JsonPrimitive(uri) &&
                            doc["version"] == JsonPrimitive(source.version) &&
                            doc["text"] == JsonPrimitive(source.text),
                    ) { "Opened document differs from authored source" }
                    stateIndex = 0
                }
                "textDocument/didChange" -> {
                    require(stateIndex >= 0 && !closed) { "Change outside open document" }
                    require(stateIndex + 1 < authoredSources.size) { "Unlisted document change" }
                    val source = authoredSources[stateIndex + 1]
                    val params = message["params"] as? JsonObject
                    val doc = document(message)
                    val changes = params?.get("contentChanges") as? JsonArray
                    val change = changes?.singleOrNull() as? JsonObject
                    require(
                        doc != null &&
                            doc["uri"] == JsonPrimitive(uri) &&
                            doc["version"] == JsonPrimitive(source.version) &&
                            change != null &&
                            change.keys == setOf("text") &&
                            change["text"] == JsonPrimitive(source.text),
                    ) { "Change differs from authored full-text source" }
                    stateIndex++
                }
                "textDocument/didClose" -> {
                    require(
                        stateIndex >= 0 &&
                            !closed &&
                            document(message)?.get("uri") == JsonPrimitive(uri),
                    ) { "Unexpected document close" }
                    closed = true
                }
                in featureMethods -> if (message.containsKey("id")) {
                    require(stateIndex >= 0 && !closed) { "Request outside open document" }
                    require(nextRequest < requests.size) { "Unlisted feature request" }
                    val request = requests[nextRequest]
                    val source = authoredSources[stateIndex]
                    require(
                        request.source == source &&
                            method == request.method &&
                            message["params"] == request.params,
                    ) { "Request differs from authored request or active source" }
                    requireAssociation(request, source)
                    bindings += bindRequest(message, index, request, source, sent, received)
                    nextRequest++
                }
                else -> if (method != null && message.containsKey("id")) {
                    require(method in setupMethods) { "Unlisted client request: $method" }
                }
            }
        }
        require(stateIndex == authoredSources.lastIndex) { "Authored source transition was not observed" }
        require(nextRequest == requests.size) { "Authored request was not observed" }
        return buildJsonObject {
            put("schemaVersion", 1)
            put(
                "sources",
                buildJsonArray {
                    authoredSources.forEach { source ->
                        add(
                            buildJsonObject {
                                put("uri", source.uri)
                                put("version", source.version)
                                put("text", source.text)
                                put("sha256", hash(source.text))
                            },
                        )
                    }
                },
            )
            put(
                "wire",
                buildJsonObject {
                    put("transmitted", RecoveryWireFrames.evidence(snapshot.transmitted))
                    put("received", RecoveryWireFrames.evidence(snapshot.received))
                },
            )
            put("bindings", buildJsonArray { bindings.forEach { add(it) } })
        }
    }

    private fun bindRequest(
        message: JsonObject,
        index: Int,
        request: WarmRecoveryRequest,
        source: ColdRecoverySource,
        sent: List<JsonObject>,
        received: List<JsonObject>,
    ): JsonObject {
        val id = message["id"]
        require(id is JsonPrimitive && (id.isString || id.content.matches(Regex("-?[0-9]+")))) {
            "Invalid request ID"
        }
        require(sent.count { it.containsKey("method") && it["id"] == id } == 1) { "Duplicate request ID" }
        val responses = received.withIndex().filter { (_, response) ->
            !response.containsKey("method") && response["id"] == id
        }
        require(responses.size == 1) { "Expected one response for request ID" }
        val response = responses.single()
        require(!response.value.containsKey("error") && response.value.containsKey("result")) {
            "Request did not succeed"
        }
        require(response.value["result"] == request.result) { "Response differs from captured SDK result" }
        return buildJsonObject {
            put("requestId", id)
            put("outgoingIndex", index)
            put("responseIndex", response.index)
            put("method", request.method)
            put("params", request.params)
            put("result", request.result)
            put("clientVersion", source.version)
            put("sourceHash", hash(source.text))
            put("incarnation", request.incarnation)
        }
    }

    private fun requireAssociation(request: WarmRecoveryRequest, source: ColdRecoverySource) {
        val uri = JsonPrimitive(source.uri)
        when (request.method) {
            "groovy/awaitDiagnostics" -> require(
                request.params["uri"] == uri && request.params["version"] == JsonPrimitive(source.version),
            ) { "Diagnostic request targets a different document or version" }
            "textDocument/semanticTokens/full",
            "textDocument/documentSymbol",
            "textDocument/hover",
            "textDocument/completion",
            -> {
                require((request.params["textDocument"] as? JsonObject)?.get("uri") == uri) {
                    "Feature request targets a different document"
                }
                if (request.method == "textDocument/hover" || request.method == "textDocument/completion") {
                    requirePosition(request.params["position"], source.text)
                }
            }
            else -> throw IllegalArgumentException("Unsupported warm replay method: ${request.method}")
        }
    }

    private fun requirePosition(value: JsonElement?, text: String) {
        val position = value as? JsonObject
        fun coordinate(name: String): Int? {
            val number = position?.get(name) as? JsonPrimitive ?: return null
            if (number.isString || !number.content.matches(Regex("0|[1-9][0-9]*"))) return null
            return number.content.toIntOrNull()
        }
        val line = coordinate("line")
        val character = coordinate("character")
        val lines = text.split('\n')
        require(line != null && character != null && line < lines.size) { "Invalid request position" }
        val lineText = lines[line].removeSuffix("\r")
        require(character <= lineText.length) { "Request position is outside source" }
        require(
            character == 0 ||
                character == lineText.length ||
                !(
                    Character.isHighSurrogate(lineText[character - 1]) &&
                        Character.isLowSurrogate(lineText[character])
                    ),
        ) { "Request position splits a surrogate pair" }
    }

    private fun document(message: JsonObject): JsonObject? =
        (message["params"] as? JsonObject)?.get("textDocument") as? JsonObject

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
