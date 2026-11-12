package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.JsonParser
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

class ScenarioNumberWireTest {
    @TempDir
    lateinit var root: Path

    @TestFactory
    fun `YAML numbers round trip through wire and response assertions`(): List<DynamicTest> = listOf(
        "123456789012345678901234567890",
        "0.123456789012345678901234567890",
        "1.234567890123456789e50",
        "-1.234567890123456789e-50",
        "1e400",
        "-0.0",
        "1.0",
        "2147483648",
    ).map { literal ->
        dynamicTest(literal) {
            val definition = JacksonScenarioParser().parseScenarioDefinition(
                """
                name: numeric-wire
                steps:
                  - request:
                      method: workspace/executeCommand
                      params: { command: echo, arguments: [{number: $literal}] }
                  - notification:
                      method: workspace/didChangeConfiguration
                      params: { settings: {number: $literal} }
                  - assert:
                      checks:
                        - { jsonPath: $.number, expect: {type: EQUALS, value: $literal} }
                        - { jsonPath: $.number, expect: {type: GTE, value: $literal} }
                        - { jsonPath: $.number, expect: {type: LTE, value: $literal} }
                """.trimIndent(),
            )
            val wire = NumericWireCapture(root, definition)
            val steps = definition.scenario.steps

            SendRequestStepExecutor().execute(steps.filterIsInstance<ScenarioStep.SendRequest>().single(), wire.context)
            val response = requireNotNull(wire.context.lastResult)
            SendNotificationStepExecutor().execute(
                steps.filterIsInstance<ScenarioStep.SendNotification>().single(),
                wire.context,
            )

            val requestNumber = wire.messages.first()["params"]?.jsonObject?.get("arguments")?.jsonArray
                ?.single()?.jsonObject?.get("number")
            val notificationNumber = wire.messages.last()["params"]?.jsonObject?.get("settings")
                ?.jsonObject?.get("number")
            listOf(requestNumber, notificationNumber).forEach { value ->
                assertThat(value?.jsonPrimitive?.isString).isFalse()
                assertThat(value?.jsonPrimitive?.content?.toBigDecimal()).isEqualByComparingTo(literal.toBigDecimal())
            }
            steps.filterIsInstance<ScenarioStep.Assert>().single().checks.forEach { check ->
                assertThat(wire.context.evaluateCheck(response, check)).isTrue()
            }
            wire.context.setVariable("echo", response)
            val interpolated = wire.context.interpolateNode(JsonPrimitive("{{echo.number}}"))
            assertThat(interpolated?.jsonPrimitive?.content?.toBigDecimal())
                .isEqualByComparingTo(literal.toBigDecimal())
        }
    }

    @Test
    fun `raw huge exponent numbers remain available to wire structural equality and field extraction`() {
        val value = Json.parseToJsonElement("1e2147483649")
        val params = Json.parseToJsonElement("""{"command":"echo","arguments":[{"number":1e2147483649}]}""")
        val wire = NumericWireCapture(root, ScenarioDefinition(Scenario(name = "huge-exponent", steps = emptyList())))

        SendRequestStepExecutor().execute(ScenarioStep.SendRequest("workspace/executeCommand", params), wire.context)

        assertThat(wire.messages.single()["params"]).isEqualTo(params)
        val response = requireNotNull(wire.context.lastResult)
        val equality = JsonCheck("$.number", JsonExpectation(ExpectationType.EQUALS, value))
        assertThat(wire.context.evaluateCheck(response, equality)).isTrue()
        wire.context.setVariable("echo", response)
        assertThat(wire.context.interpolateNode(JsonPrimitive("{{echo.number}}"))).isEqualTo(value)
    }

    @TestFactory
    fun `numeric assertion failures render exact values without floating point encoding`(): List<DynamicTest> = listOf(
        "123456789012345678901234567890" to "123456789012345678901234567890",
        "0.123456789012345678901234567890" to "0.123456789012345678901234567890",
        "1e400" to "1E+400",
        "1e2147483649" to "1e2147483649",
    ).map { (literal, rendered) ->
        dynamicTest(literal) {
            val wire =
                NumericWireCapture(root, ScenarioDefinition(Scenario(name = "numeric-mismatch", steps = emptyList())))
            val response = Json.parseToJsonElement("""{"number":$literal}""")
            val mismatch = JsonCheck("$.number", JsonExpectation(ExpectationType.EQUALS, JsonPrimitive(0)))

            assertThatThrownBy { wire.context.evaluateCheck(response, mismatch) }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("Actual: $rendered")
        }
    }
}

/** A real SDK endpoint and codec echo JSON; this peer does not assert product command support. */
private class NumericWireCapture(root: Path, definition: ScenarioDefinition) {
    val messages = mutableListOf<JsonObject>()
    private val codec = MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer::class.java)) {
        it.registerRawJsonRpcParams().preserveUntypedJsonNumbers()
    }
    private val remote: RemoteEndpoint
    val context: ScenarioContext

    init {
        remote = RemoteEndpoint(
            MessageConsumer { message ->
                check(message is RequestMessage || message is NotificationMessage)
                val captured = Json.parseToJsonElement(codec.serialize(message)).jsonObject
                messages += captured
                if (message is RequestMessage) {
                    val echoed = captured.getValue("params").jsonObject.getValue("arguments").jsonArray.single()
                    val response = ResponseMessage().apply {
                        rawId = message.rawId
                        result = JsonParser.parseString(echoed.toString())
                    }
                    remote.consume(codec.parseMessage(codec.serialize(response)))
                }
            },
            mockk<Endpoint>(),
        )
        remote.jsonHandler = codec
        codec.methodProvider = remote
        val session = mockk<LanguageServerSession>()
        every { session.endpoint } returns remote
        context = ScenarioContext(
            definition = definition,
            session = session,
            workspace = ScenarioWorkspace(root),
            jsonPathConfig = Configuration.builder().jsonProvider(JsonSmartJsonProvider())
                .mappingProvider(JsonSmartMappingProvider()).build(),
        )
    }
}
