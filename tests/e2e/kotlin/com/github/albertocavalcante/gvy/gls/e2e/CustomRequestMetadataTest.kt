// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.github.albertocavalcante.gvy.gls.testing.api.GroovyLanguageServerProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** SDK codec controls verify result observation, independently of product feature behavior. */
class CustomRequestMetadataTest {
    @Test
    fun `client metadata tracks every product custom request`() {
        assertEquals(productMethods(), customRequests(GroovyLanguageServerProtocol::class.java))
    }

    @Test
    fun `inventory includes SDK inherited and delegated requests`() {
        assertEquals(listOf("groovy/delegated", "groovy/inherited"), customRequests(DelegatingProtocol::class.java))
    }

    @TestFactory
    fun `generic custom results retain their JSON through the SDK`(): List<DynamicTest> =
        productMethods().filterNot { it in LEGACY_TYPED_METHODS }.flatMap { method ->
            RESULTS.map { (shape, expected) ->
                dynamicTest("$method $shape") {
                    assertEquals(expected, observedResult(method, expected))
                }
            }
        }

    private fun observedResult(method: String, expected: JsonElement): JsonElement {
        val codec = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(GroovyLanguageServerProtocol::class.java))
        lateinit var remote: RemoteEndpoint
        remote = RemoteEndpoint(
            MessageConsumer { message ->
                assertIs<RequestMessage>(message)
                val outgoing = Json.parseToJsonElement(codec.serialize(message)).jsonObject
                // Emit an actual JSON response so the producer cannot drop nulls or coerce numeric literals.
                val response = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", outgoing.getValue("id"))
                    put("result", expected)
                }
                remote.consume(codec.parseMessage(response.toString()))
            },
            object : Endpoint {
                override fun request(method: String, parameter: Any?): CompletableFuture<*> =
                    error("Unexpected local request: $method")

                override fun notify(method: String, parameter: Any?) {
                    error("Unexpected local notification: $method")
                }
            },
        )
        remote.jsonHandler = codec
        codec.methodProvider = remote
        val future = remote.request(method, null)
        return try {
            JsonBridge.wrapJavaObject(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    private fun productMethods(): List<String> = customRequests(GroovyLanguageServer::class.java)

    private fun customRequests(protocol: Class<*>): List<String> = ServiceEndpoints.getSupportedMethods(protocol)
        .filter { (method, metadata) -> method.startsWith("groovy/") && !metadata.isNotification }
        .keys.sorted()

    private interface ParentProtocol {
        @JsonRequest("groovy/inherited")
        fun inherited(): CompletableFuture<JsonElement>
    }

    private interface DelegatingProtocol : ParentProtocol {
        override fun inherited(): CompletableFuture<JsonElement>

        @JsonDelegate
        fun child(): ChildProtocol
    }

    private interface ChildProtocol {
        @JsonRequest("groovy/delegated")
        fun delegated(): CompletableFuture<JsonElement>

        @JsonNotification("groovy/notification")
        fun notification()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        val LEGACY_TYPED_METHODS = setOf("groovy/discoverTests", "groovy/runTest")
        val RESULTS = listOf(
            "object" to Json.parseToJsonElement("""{"value":"control","optional":null,"nested":{"missing":null}}"""),
            "array" to Json.parseToJsonElement("""[null,{"value":"control"},17]"""),
            "string" to JsonPrimitive("control"),
            "number" to Json.parseToJsonElement("123456789012345678901234567890"),
            "true" to JsonPrimitive(true),
            "false" to JsonPrimitive(false),
            "null" to JsonNull,
        )
    }
}
