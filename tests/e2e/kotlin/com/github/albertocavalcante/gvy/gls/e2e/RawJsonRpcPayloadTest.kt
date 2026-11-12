// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.JsonSyntaxException
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RawJsonRpcPayloadTest {
    @TempDir
    lateinit var root: Path

    @TestFactory
    fun `generic requests send the authored JSON value without coercion`(): List<DynamicTest> =
        payloads().map { payload ->
            dynamicTest(payload.toString()) {
                val wire = RawPayloadCapture(root)

                SendRequestStepExecutor().execute(ScenarioStep.SendRequest("initialize", payload), wire.context)

                assertThat(wire.messages.single()["params"]).isEqualTo(payload)
                assertThat(wire.messages.single()["method"]).isEqualTo(JsonPrimitive("initialize"))
            }
        }

    @TestFactory
    fun `generic notifications send the authored JSON value without coercion`(): List<DynamicTest> =
        payloads().map { payload ->
            dynamicTest(payload.toString()) {
                val wire = RawPayloadCapture(root)

                SendNotificationStepExecutor().execute(
                    ScenarioStep.SendNotification("workspace/didChangeConfiguration", payload),
                    wire.context,
                )

                assertThat(wire.messages.single()["params"]).isEqualTo(payload)
                assertThat(wire.context.lastResult).isEqualTo(payload)
            }
        }

    @Test
    fun `the default typed initialization keeps its existing SDK envelope`() {
        val wire = RawPayloadCapture(root)

        InitializeStepExecutor().execute(ScenarioStep.Initialize(), wire.context)

        val params = wire.messages.single().getValue("params").jsonObject
        assertThat(params).containsEntry("rootUri", JsonNull)
        assertThat(params).doesNotContainKeys("capabilities", "initializationOptions")
        assertThat(params["workspaceFolders"]?.jsonArray?.size).isEqualTo(1)
        assertThat(wire.context.state.initializedResult).isNotNull()
    }

    @Test
    fun `omitted request and notification parameters keep the existing SDK null envelope`() {
        val wire = RawPayloadCapture(root)

        SendRequestStepExecutor().execute(ScenarioStep.SendRequest("initialize"), wire.context)
        SendNotificationStepExecutor().execute(
            ScenarioStep.SendNotification("workspace/didChangeConfiguration"),
            wire.context,
        )

        assertThat(wire.messages).hasSize(2).allSatisfy { message ->
            assertThat(message).containsEntry("params", JsonNull)
        }
    }

    @Test
    fun `invalid notification JSON fails the step before SDK logging can hide serialization failure`() {
        val wire = RawPayloadCapture(root)

        assertThatThrownBy {
            SendNotificationStepExecutor().execute(
                ScenarioStep.SendNotification("workspace/didChangeConfiguration", JsonPrimitive(Double.NaN)),
                wire.context,
            )
        }.isInstanceOf(JsonSyntaxException::class.java)

        assertThat(wire.messages).isEmpty()
        assertThat(wire.context.lastResult).isNull()
    }

    @Test
    fun `invalid request JSON fails before the SDK allocates a pending request`() {
        val wire = RawPayloadCapture(root)

        assertThatThrownBy {
            SendRequestStepExecutor().execute(
                ScenarioStep.SendRequest("initialize", JsonPrimitive(Double.NaN)),
                wire.context,
            )
        }.isInstanceOf(JsonSyntaxException::class.java)

        assertThat(wire.messages).isEmpty()
        assertThat(wire.context.lastResult).isNull()
    }

    private fun payloads(): List<JsonElement> = listOf(
        "{}",
        """{"future":{"expect":{"snapshot":"opaque-data"},"flag":false}}""",
        """{"outer":{"explicit":null,"values":[null,{"nested":null}]}}""",
        "[null,{\"nested\":null}]",
        "null", "false", "\"text\"", "12.5", "1e2", "123456789012345678901234567890",
    ).map { Json.parseToJsonElement(it) }
}

/** A real endpoint and codec; this scripted peer accepts captures without validating product method parameters. */
private class RawPayloadCapture(root: Path) {
    val messages = mutableListOf<JsonObject>()
    private val codec = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer::class.java)) {
        it.registerRawJsonRpcParams()
    }
    private val remote: RemoteEndpoint
    val context: ScenarioContext

    init {
        remote = RemoteEndpoint(
            MessageConsumer { message ->
                check(message is RequestMessage || message is NotificationMessage)
                messages += Json.parseToJsonElement(codec.serialize(message)).jsonObject
                if (message is RequestMessage) {
                    val response = ResponseMessage().apply {
                        rawId = message.rawId
                        result = InitializeResult(ServerCapabilities())
                    }
                    remote.consume(codec.parseMessage(codec.serialize(response)))
                }
            },
            mockk<Endpoint>(),
        )
        remote.jsonHandler = codec
        codec.methodProvider = remote
        val session = mockk<LanguageServerSession>()
        every { session.server } returns ServiceEndpoints.toServiceObject(remote, LanguageServer::class.java)
        every { session.endpoint } returns remote
        every { session.process } returns null
        context = ScenarioContext(
            definition = ScenarioDefinition(Scenario(name = "raw-payloads", steps = emptyList())),
            session = session,
            workspace = ScenarioWorkspace(root),
            jsonPathConfig = Configuration.builder()
                .jsonProvider(JsonSmartJsonProvider()).mappingProvider(JsonSmartMappingProvider()).build(),
        ).also { it.registerBuiltInVariables() }
    }
}
