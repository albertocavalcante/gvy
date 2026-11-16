// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal data class ColdRecoverySource(val uri: String, val version: Int, val text: String)
internal data class ColdRecoveryRequest(val method: String, val params: JsonObject, val result: JsonElement)

/** Audits captured frames independently of the SDK; it does not participate in transport. */
internal object ColdRecoveryWire {
    fun bind(
        snapshot: WireEvidenceSnapshot,
        source: ColdRecoverySource,
        requests: List<ColdRecoveryRequest>,
    ): JsonObject {
        require(requests.isNotEmpty()) { "Cold replay requires at least one request" }
        require(snapshot.received.endOfInputObserved) { "Server output EOF was not observed" }
        val sent = RecoveryWireFrames.frames(snapshot.transmitted)
        val received = RecoveryWireFrames.frames(snapshot.received)
        val uri = JsonPrimitive(source.uri)
        val matchingOpens = sent.withIndex().filter { (_, message) ->
            message["method"] == JsonPrimitive("textDocument/didOpen") &&
                document(message)?.get("uri") == uri
        }
        require(matchingOpens.size == 1) { "Expected one cold document open" }
        val open = matchingOpens.single()
        val openedDocument = requireNotNull(document(open.value))
        require(
            openedDocument["version"] == JsonPrimitive(source.version) &&
                openedDocument["text"] == JsonPrimitive(source.text),
        ) {
            "Cold document does not match authored source"
        }
        require(
            sent.none { message ->
                message["method"] == JsonPrimitive("textDocument/didChange") && document(message)?.get("uri") == uri
            },
        ) { "Cold document was changed" }
        val hash = MessageDigest.getInstance("SHA-256").digest(source.text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        var previousIndex = open.index
        val bindings = requests.map { request ->
            requireSourceAssociation(request, source)
            val candidates = sent.withIndex().filter { (_, message) ->
                message["method"] == JsonPrimitive(request.method) && message["params"] == request.params &&
                    message.containsKey("id")
            }
            require(candidates.size == 1) { "Expected one matching request for ${request.method}" }
            val outgoing = candidates.single()
            require(outgoing.index > previousIndex) { "Request is not in authored order after didOpen" }
            previousIndex = outgoing.index
            val id = requireNotNull(outgoing.value["id"])
            require(id is JsonPrimitive && (id.isString || id.content.matches(Regex("-?[0-9]+")))) {
                "Invalid request ID"
            }
            require(sent.count { it.containsKey("method") && it["id"] == id } == 1) { "Duplicate request ID" }
            val responses = received.withIndex().filter { (_, message) ->
                !message.containsKey("method") && message["id"] == id
            }
            require(responses.size == 1) { "Expected one response for request ID" }
            val response = responses.single()
            require(!response.value.containsKey("error") && response.value.containsKey("result")) {
                "Request did not succeed"
            }
            require(response.value["result"] == request.result) { "Response differs from captured SDK result" }
            buildJsonObject {
                put("requestId", id)
                put("outgoingIndex", outgoing.index)
                put("responseIndex", response.index)
                put("method", request.method)
                put("params", request.params)
                put("result", request.result)
                put("clientVersion", source.version)
                put("sourceHash", hash)
            }
        }
        require(
            sent.withIndex().none { (index, message) ->
                index < previousIndex && message["method"] == JsonPrimitive("textDocument/didClose") &&
                    document(message)?.get("uri") == uri
            },
        ) { "Cold document closed before a bound request" }
        return buildJsonObject {
            put("schemaVersion", 1)
            put(
                "source",
                buildJsonObject {
                    put("uri", source.uri)
                    put("version", source.version)
                    put("text", source.text)
                    put("sha256", hash)
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

    private fun requireSourceAssociation(request: ColdRecoveryRequest, source: ColdRecoverySource) {
        val uri = JsonPrimitive(source.uri)
        when (request.method) {
            "textDocument/semanticTokens/full",
            "textDocument/hover",
            "textDocument/documentSymbol",
            "textDocument/completion",
            -> require((request.params["textDocument"] as? JsonObject)?.get("uri") == uri) {
                "Feature request targets a different document"
            }
            "groovy/awaitDiagnostics" -> require(
                request.params["uri"] == uri && request.params["version"] == JsonPrimitive(source.version),
            ) { "Diagnostic request targets a different document or version" }
            else -> throw IllegalArgumentException("Unsupported cold replay method: ${request.method}")
        }
    }

    private fun document(message: JsonObject): JsonObject? =
        (message["params"] as? JsonObject)?.get("textDocument") as? JsonObject
}
