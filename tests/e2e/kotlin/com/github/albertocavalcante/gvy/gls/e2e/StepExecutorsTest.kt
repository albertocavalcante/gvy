package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.services.Health
import com.github.albertocavalcante.gvy.gls.services.StatusNotification
import com.github.albertocavalcante.gvy.gls.testing.client.NotificationEnvelope
import com.github.albertocavalcante.gvy.gls.testing.client.NotificationSnapshot
import com.github.albertocavalcante.gvy.gls.testing.client.PredicateFailure
import com.github.albertocavalcante.gvy.gls.testing.client.WaitResult
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture

class StepExecutorsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val session: LanguageServerSession = mockk(relaxed = true)
    private val workspace: ScenarioWorkspace = mockk(relaxed = true)
    private val definition: ScenarioDefinition = mockk(relaxed = true)
    private lateinit var context: ScenarioContext

    @BeforeEach
    fun setup() {
        val jsonPathConfig = Configuration.builder()
            .jsonProvider(JsonSmartJsonProvider())
            .mappingProvider(JsonSmartMappingProvider())
            .build()

        context = ScenarioContext(
            definition = definition,
            session = session,
            workspace = workspace,
            json = json,
            jsonPathConfig = jsonPathConfig,
        )
    }

    @Test
    fun `InitializeStepExecutor should interpolate options and store result`() {
        // Arrange
        val optionsNode = buildJsonObject {
            put("param", "{{myVar}}")
        }
        val step = ScenarioStep.Initialize(
            initializationOptions = optionsNode,
        )
        context.variables["myVar"] = JsonPrimitive("interpolatedValue")

        val future = CompletableFuture<InitializeResult>()
        val initResult = InitializeResult()
        future.complete(initResult)

        val paramsSlot = slot<InitializeParams>()
        every { session.server.initialize(capture(paramsSlot)) } returns future

        val executor = InitializeStepExecutor()

        // Act
        executor.execute(step, context)

        // Assert
        val capturedParams = paramsSlot.captured
        val initOptions = capturedParams.initializationOptions as Map<*, *>
        assertThat(initOptions["param"]).isEqualTo("interpolatedValue")

        assertThat(context.state.initializedResult).isEqualTo(initResult)

        // Use a simpler assertion for JsonElement equality
        val expectedOptions = buildJsonObject { put("param", "interpolatedValue") }
        assertThat(context.variables["client.initializationOptions"]).isEqualTo(expectedOptions)
    }

    @Test
    fun `SendRequestStepExecutor should normalize response and extract variables`() {
        // Arrange
        val step = ScenarioStep.SendRequest(
            method = "test/request",
            extract = listOf(JsonExtraction("extractedVar", "$.result.value")),
        )

        // Mock a CompletableFuture that returns a raw LinkedHashMap (what LSP4J returns for 'Any')
        val responseMap = mapOf("result" to mapOf("value" to "success"))
        val future = CompletableFuture.completedFuture(responseMap as Any)

        every { session.endpoint.request("test/request", any()) } returns future

        val executor = SendRequestStepExecutor()

        // Act
        executor.execute(step, context)

        // Assert
        assertThat(context.variables.containsKey("extractedVar")).isTrue
        val extractedVar = context.variables["extractedVar"]
        assertThat(extractedVar is JsonPrimitive).isTrue
        assertThat((extractedVar as JsonPrimitive).content).isEqualTo("success")

        val lastResult = context.lastResult
        assertThat(lastResult).isInstanceOf(JsonObject::class.java)
        val jsonResult = lastResult as JsonObject

        // Manual traversal check
        val resultObj = jsonResult["result"] as? JsonObject
        assertThat(resultObj?.get("value")?.jsonPrimitive?.content).isEqualTo("success")
    }

    @Test
    fun `OpenDocumentStepExecutor should send didOpen notification`() {
        // Arrange
        val step = ScenarioStep.OpenDocument(
            uri = "file:///test.groovy",
            languageId = "groovy",
            version = 1,
            text = "println 'hello {{name}}'",
        )
        context.variables["name"] = JsonPrimitive("world")

        val executor = OpenDocumentStepExecutor()

        // Act
        executor.execute(step, context)

        // Assert
        verify {
            session.server.textDocumentService.didOpen(
                match {
                    it.textDocument.text == "println 'hello world'" &&
                        it.textDocument.uri == "file:///test.groovy"
                },
            )
        }
    }

    @Test
    fun `a string that is exactly one placeholder substitutes the typed value`() {
        context.variables["pos"] = buildJsonObject {
            put("line", 1)
            put("character", 2)
        }
        context.variables["count"] = JsonPrimitive(3)
        context.variables["items"] = JsonArray(listOf(JsonPrimitive("a")))

        val node = context.interpolateNode(
            buildJsonObject {
                put("position", "{{pos}}")
                put("count", "{{ count }}")
                put("items", "{{items}}")
                put("line", "{{pos.line}}")
                put("label", "line {{pos.line}}")
            },
        )

        assertThat(node).isEqualTo(
            buildJsonObject {
                put("position", context.variables["pos"]!!)
                put("count", 3)
                put("items", context.variables["items"]!!)
                put("line", 1)
                put("label", "line 1")
            },
        )
    }

    @Test
    fun `OpenDocumentStepExecutor strips markers and registers cursors`() {
        val step = ScenarioStep.OpenDocument(
            uri = "file:///ws/src/Main.groovy",
            text = "def {{name}} = |^|1\nprintln |^use|{{name}}\n",
        )
        context.variables["name"] = JsonPrimitive("greeting")

        OpenDocumentStepExecutor().execute(step, context)

        verify {
            session.server.textDocumentService.didOpen(
                match { it.textDocument.text == "def greeting = 1\nprintln greeting\n" },
            )
        }
        assertThat(context.lastResult!!.let { (it as JsonObject)["text"]!!.jsonPrimitive.content })
            .isEqualTo("def greeting = 1\nprintln greeting\n")
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor}}"))).isEqualTo(position(0, 15))
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.use}}"))).isEqualTo(position(1, 8))
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.use.line}}"))).isEqualTo(JsonPrimitive(1))
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.character}}"))).isEqualTo(JsonPrimitive(15))
        assertThat(context.interpolateString("{{cursor.use.uri}}")).isEqualTo("file:///ws/src/Main.groovy")
        assertThat(context.interpolateString("{{cursor.use.path}}")).isEqualTo("/ws/src/Main.groovy")
    }

    @Test
    fun `OpenDocumentStepExecutor reports duplicate marker names`() {
        val step = ScenarioStep.OpenDocument(uri = "file:///ws/A.groovy", text = "|^a|x |^a|y")

        assertThatThrownBy { OpenDocumentStepExecutor().execute(step, context) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("file:///ws/A.groovy")
            .hasMessageContaining("|^a|")
    }

    @Test
    fun `full-text ChangeDocumentStepExecutor strips markers and rebinds cursors`() {
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(uri = "file:///ws/A.groovy", text = "|^a|x"),
            context,
        )

        ChangeDocumentStepExecutor().execute(
            ScenarioStep.ChangeDocument(uri = "file:///ws/A.groovy", version = 2, text = "yy\nz|^a|x"),
            context,
        )

        verify {
            session.server.textDocumentService.didChange(match { it.contentChanges.single().text == "yy\nzx" })
        }
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.a}}"))).isEqualTo(position(1, 1))
    }

    @Test
    fun `unknown cursor names fail with the registered names`() {
        context.registerCursors("file:///ws/A.groovy", null, listOf(CursorMarker("known", 0, 0)))

        assertThatThrownBy { context.interpolateString("{{cursor.missing}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing")
            .hasMessageContaining("known")
        assertThatThrownBy { context.interpolateString("{{cursor}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("|^|")
        assertThatThrownBy { context.interpolateString("{{cursor.known.column}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("column")
    }

    @Test
    fun `CompletionStepExecutor sends a cursor position`() {
        context.registerCursors("file:///ws/A.groovy", null, listOf(CursorMarker("dot", 4, 7)))
        val params = slot<Any>()
        every { session.endpoint.request("textDocument/completion", capture(params)) } returns
            CompletableFuture.completedFuture(emptyList<Any>())

        CompletionStepExecutor().execute(
            ScenarioStep.Completion(uri = "file:///ws/A.groovy", position = JsonPrimitive("{{cursor.dot}}")),
            context,
        )

        val sent = params.captured as Map<*, *>
        assertThat(sent["position"]).isEqualTo(mapOf("line" to 4L, "character" to 7L))
    }

    @Test
    fun `RenameStepExecutor sends a cursor position`() {
        context.registerCursors("file:///ws/A.groovy", null, listOf(CursorMarker(null, 2, 3)))
        val params = slot<Any>()
        every { session.endpoint.request("textDocument/rename", capture(params)) } returns
            CompletableFuture.completedFuture(emptyMap<String, Any>())

        RenameStepExecutor().execute(
            ScenarioStep.Rename(uri = "file:///ws/A.groovy", position = JsonPrimitive("{{cursor}}"), newName = "b"),
            context,
        )

        val sent = params.captured as Map<*, *>
        assertThat(sent["position"]).isEqualTo(mapOf("line" to 2L, "character" to 3L))
    }

    @Test
    fun `position and line or character together are rejected`() {
        assertThatThrownBy {
            CompletionStepExecutor().execute(
                ScenarioStep.Completion(uri = "file:///ws/A.groovy", line = 1, position = position(0, 0)),
                context,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("either")
    }

    private fun position(line: Int, character: Int): JsonObject = buildJsonObject {
        put("line", line)
        put("character", character)
    }

    @Test
    fun `notification timeout reports the rejected payloads and server status`() {
        val now = Instant.now()
        val loading = MessageParams(MessageType.Info, "Resolving build dependencies...")
        val failed = StatusNotification(health = Health.Warning, message = "Could not install Gradle distribution")
        val result = WaitResult(
            envelope = null,
            receivedDuringWait = listOf(NotificationSnapshot("groovy/status", now, failed)),
            matchedMethodButFailed = listOf(
                PredicateFailure(NotificationEnvelope(1, "window/showMessage", loading, now), null),
            ),
            elapsedMs = 42,
        )

        val message = timeoutMessage("window/showMessage", result)

        assertThat(message)
            .startsWith("Timeout waiting for notification 'window/showMessage' after 42 ms")
            .contains("Resolving build dependencies...")
            .contains("\"health\":\"warning\"")
            .contains("Could not install Gradle distribution")
    }

    @Test
    fun `notification timeout says when nothing arrived`() {
        val result =
            WaitResult(
                envelope = null,
                receivedDuringWait = emptyList(),
                matchedMethodButFailed = emptyList(),
                elapsedMs = 7,
            )

        assertThat(timeoutMessage("groovy/status", result))
            .isEqualTo(
                "Timeout waiting for notification 'groovy/status' after 7 ms; " +
                    "no unconsumed 'groovy/status' notification arrived",
            )
    }
}
