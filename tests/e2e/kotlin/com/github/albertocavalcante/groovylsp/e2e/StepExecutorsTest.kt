package com.github.albertocavalcante.groovylsp.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
}
