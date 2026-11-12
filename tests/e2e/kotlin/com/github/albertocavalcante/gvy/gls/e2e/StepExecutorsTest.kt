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
import kotlinx.serialization.json.jsonObject
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
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture

class StepExecutorsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val session: LanguageServerSession = mockk(relaxed = true)

    @TempDir
    lateinit var workspaceRoot: Path
    private lateinit var workspace: ScenarioWorkspace
    private val definition: ScenarioDefinition = mockk(relaxed = true)
    private lateinit var context: ScenarioContext

    @BeforeEach
    fun setup() {
        workspace = ScenarioWorkspace(workspaceRoot)
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
    fun `opening a directory as a fixture fails before didOpen`() {
        Files.createDirectory(workspaceRoot.resolve("fixture.groovy"))

        assertThatThrownBy {
            OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "fixture.groovy"), context)
        }.isInstanceOf(IOException::class.java).hasMessageContaining("fixture.groovy")
            .hasCauseInstanceOf(IOException::class.java)
        verify(exactly = 0) { session.server.textDocumentService.didOpen(any()) }
    }

    @Test
    fun `opening a missing fixture without text fails before didOpen`() {
        assertThatThrownBy {
            OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "missing.groovy"), context)
        }.isInstanceOf(IOException::class.java).hasMessageContaining("missing.groovy")
            .hasCauseInstanceOf(IOException::class.java)
        verify(exactly = 0) { session.server.textDocumentService.didOpen(any()) }
    }

    @Test
    fun `opening an explicit empty buffer does not read a missing file`() {
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "new.groovy", text = ""), context)

        verify(exactly = 1) { session.server.textDocumentService.didOpen(match { it.textDocument.text == "" }) }
    }

    @Test
    fun `explicit text supports non-file document URIs`() {
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(uri = "untitled:New.groovy", text = "println 42"),
            context,
        )

        verify(exactly = 1) {
            session.server.textDocumentService.didOpen(
                match { it.textDocument.uri == "untitled:New.groovy" && it.textDocument.text == "println 42" },
            )
        }
    }

    @Test
    fun `non-file document URI without text fails before didOpen`() {
        assertThatThrownBy {
            OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = "untitled:New.groovy"), context)
        }.isInstanceOf(FileSystemNotFoundException::class.java)
        verify(exactly = 0) { session.server.textDocumentService.didOpen(any()) }
    }

    @Test
    fun `opening a readable fixture sends its contents`() {
        Files.writeString(workspaceRoot.resolve("source.groovy"), "println 42")
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "source.groovy"), context)

        verify(exactly = 1) {
            session.server.textDocumentService.didOpen(match { it.textDocument.text == "println 42" })
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
            path = "src/Main.groovy",
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
        assertThat(context.interpolateString("{{cursor.use.uri}}")).isEqualTo(workspace.resolveUri("src/Main.groovy"))
        assertThat(context.interpolateString("{{cursor.use.path}}")).isEqualTo("src/Main.groovy")
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
        context.registerCursors("file:///ws/A.groovy", listOf(CursorMarker("known", 0, 0)))

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
        context.registerCursors("file:///ws/A.groovy", listOf(CursorMarker("dot", 4, 7)))
        val params = slot<RawJsonRpcParams>()
        every { session.endpoint.request("textDocument/completion", capture(params)) } returns
            CompletableFuture.completedFuture(emptyList<Any>())

        CompletionStepExecutor().execute(
            ScenarioStep.Completion(uri = "file:///ws/A.groovy", position = JsonPrimitive("{{cursor.dot}}")),
            context,
        )

        val sent = params.captured.json.jsonObject
        assertThat(sent["position"]).isEqualTo(position(4, 7))
    }

    @Test
    fun `RenameStepExecutor sends a cursor position`() {
        context.registerCursors("file:///ws/A.groovy", listOf(CursorMarker(null, 2, 3)))
        val params = slot<RawJsonRpcParams>()
        every { session.endpoint.request("textDocument/rename", capture(params)) } returns
            CompletableFuture.completedFuture(emptyMap<String, Any>())

        RenameStepExecutor().execute(
            ScenarioStep.Rename(uri = "file:///ws/A.groovy", position = JsonPrimitive("{{cursor}}"), newName = "b"),
            context,
        )

        val sent = params.captured.json.jsonObject
        assertThat(sent["position"]).isEqualTo(position(2, 3))
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

    @Test
    fun `new text for a document drops that document's old cursors`() {
        val a = workspace.resolveUri("A.groovy")
        val b = workspace.resolveUri("B.groovy")
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = a, text = "|^a|x |^gone|y"), context)
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = b, text = "|^b|z"), context)

        ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(uri = a, version = 2, text = "x|^a|"), context)

        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.a}}"))).isEqualTo(position(0, 1))
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.b}}"))).isEqualTo(position(0, 0))
        assertThatThrownBy { context.interpolateString("{{cursor.gone}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("gone")

        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = a, text = "no markers"), context)

        assertThatThrownBy { context.interpolateString("{{cursor.a}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.b}}"))).isEqualTo(position(0, 0))
    }

    @Test
    fun `new text drops old cursors even when the URI is spelled differently`() {
        // Inline files register under the normalized URI; scenarios often write `{{workspace.uri}}/Jenkinsfile`.
        context.registerCursors(workspace.resolveUri("Jenkinsfile"), listOf(CursorMarker("old", 0, 0)))
        val doubleSlashUri = workspace.rootUri + "/Jenkinsfile"
        assertThat(doubleSlashUri).contains("//Jenkinsfile")

        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = doubleSlashUri, text = "x|^new|"), context)

        assertThatThrownBy { context.interpolateString("{{cursor.old}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("old")
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.new}}"))).isEqualTo(position(0, 1))
        assertThat(context.interpolateString("{{cursor.new.path}}")).isEqualTo("Jenkinsfile")
    }

    @Test
    fun `openDocument without text keeps the inline-file cursors`() {
        java.nio.file.Files.writeString(workspaceRoot.resolve("A.groovy"), "class A {}")
        context.registerCursors(workspace.resolveUri("A.groovy"), listOf(CursorMarker("decl", 0, 6)))

        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "A.groovy"), context)

        verify { session.server.textDocumentService.didOpen(match { it.textDocument.text == "class A {}" }) }
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.decl}}"))).isEqualTo(position(0, 6))
    }

    @Test
    fun `Kotlin and YAML openDocument without text send the same fixture contents`() {
        java.nio.file.Files.writeString(workspaceRoot.resolve("A.groovy"), "class A {}")
        val kotlinStep = scenario(source = "scenarios/_dsl.yaml") { openDocument("A.groovy") }
            .scenario.steps.single() as ScenarioStep.OpenDocument
        val yamlStep = JacksonScenarioParser().parseScenarioDefinition(
            """
            name: fixture-open
            steps:
              - openDocument: { path: A.groovy }
            """.trimIndent(),
        ).scenario.steps.single() as ScenarioStep.OpenDocument

        OpenDocumentStepExecutor().execute(kotlinStep, context)
        OpenDocumentStepExecutor().execute(yamlStep, context)

        verify(exactly = 2) {
            session.server.textDocumentService.didOpen(match { it.textDocument.text == "class A {}" })
        }
    }

    @Test
    fun `Kotlin openDocument with explicit empty text opens an empty buffer over the fixture`() {
        java.nio.file.Files.writeString(workspaceRoot.resolve("A.groovy"), "class A {}")
        val step = scenario(source = "scenarios/_dsl.yaml") { openDocument("A.groovy", text = "") }
            .scenario.steps.single() as ScenarioStep.OpenDocument

        OpenDocumentStepExecutor().execute(step, context)

        verify { session.server.textDocumentService.didOpen(match { it.textDocument.text.isEmpty() }) }
    }

    @Test
    fun `cursor path is workspace-relative and decoded however the document was named`() {
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(path = "./src/My Café.groovy", text = "|^byPath|x"),
            context,
        )
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(uri = workspace.resolveUri("src/Ünï code.groovy"), text = "|^byUri|x"),
            context,
        )

        assertThat(context.interpolateString("{{cursor.byPath.path}}")).isEqualTo("src/My Café.groovy")
        assertThat(context.interpolateString("{{cursor.byUri.path}}")).isEqualTo("src/Ünï code.groovy")
        assertThat(context.interpolateString("{{cursor.byUri.uri}}")).contains("%20")
    }

    @Test
    fun `cursor path outside the workspace fails clearly`() {
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(uri = "file:///elsewhere/A.groovy", text = "|^out|x"),
            context,
        )

        assertThat(context.interpolateString("{{cursor.out.uri}}")).isEqualTo("file:///elsewhere/A.groovy")
        assertThatThrownBy { context.interpolateString("{{cursor.out.path}}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("outside the workspace")
    }

    @Test
    fun `markers in incremental changes are rejected before anything is sent`() {
        val step = ScenarioStep.ChangeDocument(
            uri = "file:///ws/A.groovy",
            version = 2,
            contentChanges = listOf(TestTextDocumentContentChangeEvent(text = "x|^a|")),
        )

        assertThatThrownBy { ChangeDocumentStepExecutor().execute(step, context) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("|^a|")
            .hasMessageContaining("contentChanges")
        verify(exactly = 0) { session.server.textDocumentService.didChange(any()) }
    }

    @Test
    fun `markers in saveDocument text are rejected before anything is sent`() {
        val step = ScenarioStep.SaveDocument(uri = "file:///ws/A.groovy", text = "x|^|")

        assertThatThrownBy { SaveDocumentStepExecutor().execute(step, context) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("|^|")
            .hasMessageContaining("saveDocument")
        verify(exactly = 0) { session.server.textDocumentService.didSave(any()) }
    }

    @Test
    fun `cursor is a reserved variable name`() {
        assertThatThrownBy { context.saveResult("cursor", JsonPrimitive(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved")
        assertThatThrownBy { context.setVariable("cursor.a", JsonPrimitive(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reserved")
        // Names that merely start with the word stay usable.
        context.setVariable("cursorCount", JsonPrimitive(1))
    }

    @Test
    fun `a placeholder whose value contains a marker becomes a marker`() {
        context.variables["m"] = JsonPrimitive("|^fromVar|")

        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(uri = workspace.resolveUri("A.groovy"), text = "def x = {{m}}1"),
            context,
        )

        verify { session.server.textDocumentService.didOpen(match { it.textDocument.text == "def x = 1" }) }
        assertThat(context.interpolateNode(JsonPrimitive("{{cursor.fromVar}}"))).isEqualTo(position(0, 8))
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

    @Test
    fun `quiet snapshot check matches on path existence only and never touches snapshot files`() {
        val check = JsonCheck("$.a", JsonExpectation(snapshot = "never/read.md"))
        val node = buildJsonObject { put("a", JsonPrimitive("x")) }

        // No snapshotDir property is set here: a quiet check must not need it, nor compare or write.
        assertThat(context.evaluateCheck(node, check, quiet = true)).isTrue()
        assertThat(context.evaluateCheck(buildJsonObject {}, check, quiet = true)).isFalse()
    }

    @Test
    fun `non-quiet snapshot check compares and fails when the snapshot is missing`() {
        val check = JsonCheck("$.a", JsonExpectation(snapshot = "x.md"))
        val node = buildJsonObject { put("a", JsonPrimitive("x")) }

        assertThatThrownBy { context.evaluateCheck(node, check) }.hasMessageContaining("not found")
    }
}
