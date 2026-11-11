package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.jayway.jsonpath.Configuration
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path

class DocumentVersionStepTest {
    @TempDir
    lateinit var root: Path

    private lateinit var context: ScenarioContext
    private lateinit var documents: DocumentProvider
    private val sentVersions = mutableListOf<Int>()
    private val sentUris = mutableListOf<String>()

    @BeforeEach
    fun connectDocumentNotifications() {
        documents = DocumentProvider()
        val service = mockk<TextDocumentService>(relaxed = true)
        val server = mockk<LanguageServer>()
        val session = mockk<LanguageServerSession>()
        every { server.textDocumentService } returns service
        every { session.server } returns server
        every { service.didOpen(any()) } answers {
            val document = firstArg<DidOpenTextDocumentParams>().textDocument
            documents.put(URI.create(document.uri), document.text, document.version)
        }
        every { service.didChange(any()) } answers {
            val params = firstArg<DidChangeTextDocumentParams>()
            sentVersions += params.textDocument.version
            sentUris += params.textDocument.uri
            documents.update(
                URI.create(params.textDocument.uri),
                params.contentChanges.lastOrNull()?.text,
                params.textDocument.version,
            )
        }
        every { service.didClose(any()) } answers {
            documents.remove(URI.create(firstArg<DidCloseTextDocumentParams>().textDocument.uri))
        }
        context = ScenarioContext(
            definition = ScenarioDefinition(Scenario(name = "document-versions", steps = emptyList())),
            session = session,
            workspace = ScenarioWorkspace(root),
            jsonPathConfig = Configuration.defaultConfiguration(),
        )
    }

    @Test
    fun `YAML default changes advance the opened document`() {
        val definition = JacksonScenarioParser().parseScenarioDefinition(
            """
            name: version-defaults
            steps:
              - openDocument: {path: A.groovy, text: first}
              - changeDocument: {path: A.groovy, text: second}
              - changeDocument: {path: A.groovy, text: third}
            """.trimIndent(),
        )
        executeDocumentSteps(definition)
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("third")
        assertThat(sentVersions).containsExactly(DEFAULT_OPEN_VERSION + 1, DEFAULT_OPEN_VERSION + 2)
    }

    @Test
    fun `Kotlin default changes advance the opened document`() {
        executeDocumentSteps(
            scenario(source = "version-defaults.yaml") {
                openDocument("A.groovy", "first")
                changeDocument("A.groovy", "second")
                changeDocument("A.groovy", "third")
            },
        )
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("third")
        assertThat(sentVersions).containsExactly(DEFAULT_OPEN_VERSION + 1, DEFAULT_OPEN_VERSION + 2)
    }

    @Test
    fun `explicit versions retain their wire values`() {
        executeDocumentSteps(
            scenario(source = "explicit-versions.yaml") {
                openDocument("A.groovy", "first", version = HIGH_OPEN_VERSION)
                changeDocument("A.groovy", "second", version = HIGH_OPEN_VERSION + 1)
                changeDocument("A.groovy", "third", version = HIGH_OPEN_VERSION + 2)
            },
        )
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("third")
        assertThat(sentVersions).containsExactly(HIGH_OPEN_VERSION + 1, HIGH_OPEN_VERSION + 2)
    }

    @Test
    fun `automatic changes follow explicit version jumps`() {
        executeDocumentSteps(
            scenario(source = "version-jump.yaml") {
                openDocument("A.groovy", "first")
                changeDocument("A.groovy", "second", version = HIGH_OPEN_VERSION)
                changeDocument("A.groovy", "third")
            },
        )
        assertThat(sentVersions).containsExactly(HIGH_OPEN_VERSION, HIGH_OPEN_VERSION + 1)
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("third")
    }

    @Test
    fun `explicit obsolete versions remain testable without lowering the next automatic version`() {
        executeDocumentSteps(
            scenario(source = "obsolete-version.yaml") {
                openDocument("A.groovy", "first")
                changeDocument("A.groovy", "second", version = HIGH_OPEN_VERSION)
                changeDocument("A.groovy", "ignored", version = DEFAULT_OPEN_VERSION)
                changeDocument("A.groovy", "current")
            },
        )
        assertThat(sentVersions).containsExactly(HIGH_OPEN_VERSION, DEFAULT_OPEN_VERSION, HIGH_OPEN_VERSION + 1)
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("current")
    }

    @Test
    fun `document version sequences are independent`() {
        executeDocumentSteps(
            scenario(source = "independent-versions.yaml") {
                openDocument("A.groovy", "a")
                openDocument("B.groovy", "b", version = HIGH_OPEN_VERSION)
                changeDocument("A.groovy", "a2")
                changeDocument("B.groovy", "b2")
                changeDocument("A.groovy", "a3")
            },
        )
        assertThat(sentVersions)
            .containsExactly(DEFAULT_OPEN_VERSION + 1, HIGH_OPEN_VERSION + 1, DEFAULT_OPEN_VERSION + 2)
    }

    @Test
    fun `closing and reopening resets the automatic version lifecycle`() {
        executeDocumentSteps(
            scenario(source = "reopened-versions.yaml") {
                openDocument("A.groovy", "old", version = HIGH_OPEN_VERSION)
                closeDocument("A.groovy")
                openDocument("A.groovy", "new")
                changeDocument("A.groovy", "edited")
            },
        )
        assertThat(sentVersions).containsExactly(DEFAULT_OPEN_VERSION + 1)
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("edited")
    }

    @Test
    fun `automatic version without an open fails before a notification is sent`() {
        assertThatThrownBy {
            ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(path = "A.groovy", text = "new"), context)
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("earlier openDocument")
        assertThat(sentVersions).isEmpty()
    }

    @Test
    fun `version overflow fails before a notification is sent`() {
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(path = "A.groovy", text = "first", version = Int.MAX_VALUE),
            context,
        )
        assertThatThrownBy {
            ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(path = "A.groovy", text = "new"), context)
        }.isInstanceOf(ArithmeticException::class.java)
        assertThat(sentVersions).isEmpty()
        assertThat(documents.get(URI.create(context.workspace.resolveUri("A.groovy")))).isEqualTo("first")
    }

    @Test
    fun `invalid changes do not consume an automatic version`() {
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "A.groovy", text = "first"), context)
        assertThatThrownBy {
            ChangeDocumentStepExecutor().execute(
                ScenarioStep.ChangeDocument(
                    path = "A.groovy",
                    contentChanges = listOf(TestTextDocumentContentChangeEvent(text = "x|^cursor|")),
                ),
                context,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(path = "A.groovy", text = "second"), context)
        assertThat(sentVersions).containsExactly(DEFAULT_OPEN_VERSION + 1)
    }

    @Test
    fun `version-only changes retain text while advancing the version`() {
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(path = "A.groovy", text = "first"), context)
        ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(path = "A.groovy"), context)
        val snapshot = documents.getSnapshot(URI.create(context.workspace.resolveUri("A.groovy")))
        assertThat(snapshot?.content).isEqualTo("first")
        assertThat(snapshot?.version).isEqualTo(DEFAULT_OPEN_VERSION + 1)
    }

    @Test
    fun `equivalent URI spellings share automatic versions without rewriting the wire URI`() {
        val original = context.workspace.resolveUri("A.groovy")
        val alias = original.replace("file:///", "file:/")
        assertThat(alias).isNotEqualTo(original)
        assertThat(URI.create(alias)).isEqualTo(URI.create(original))
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = original, text = "first"), context)
        ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(uri = alias, text = "second"), context)
        assertThat(sentUris).containsExactly(alias)
        assertThat(sentVersions).containsExactly(DEFAULT_OPEN_VERSION + 1)
        assertThat(documents.get(URI.create(original))).isEqualTo("second")
    }

    @Test
    fun `equivalent URI close clears the automatic version lifecycle`() {
        val original = context.workspace.resolveUri("A.groovy")
        val alias = original.replace("file:///", "file:/")
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = original, text = "first"), context)
        CloseDocumentStepExecutor().execute(ScenarioStep.CloseDocument(uri = alias), context)
        assertThatThrownBy {
            ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(uri = original, text = "closed"), context)
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("earlier openDocument")
        assertThat(sentVersions).isEmpty()
        assertThat(documents.get(URI.create(original))).isNull()
    }

    @Test
    fun `equivalent URI reopen resets versions for every spelling`() {
        val original = context.workspace.resolveUri("A.groovy")
        val alias = original.replace("file:///", "file:/")
        OpenDocumentStepExecutor().execute(
            ScenarioStep.OpenDocument(uri = original, text = "old", version = HIGH_OPEN_VERSION),
            context,
        )
        CloseDocumentStepExecutor().execute(ScenarioStep.CloseDocument(uri = alias), context)
        OpenDocumentStepExecutor().execute(ScenarioStep.OpenDocument(uri = alias, text = "new"), context)
        ChangeDocumentStepExecutor().execute(ScenarioStep.ChangeDocument(uri = original, text = "edited"), context)
        assertThat(sentUris).containsExactly(original)
        assertThat(sentVersions).containsExactly(DEFAULT_OPEN_VERSION + 1)
        assertThat(documents.get(URI.create(alias))).isEqualTo("edited")
    }

    private fun executeDocumentSteps(definition: ScenarioDefinition) {
        definition.scenario.steps.forEach { step ->
            when (step) {
                is ScenarioStep.OpenDocument -> OpenDocumentStepExecutor().execute(step, context)
                is ScenarioStep.ChangeDocument -> ChangeDocumentStepExecutor().execute(step, context)
                is ScenarioStep.CloseDocument -> CloseDocumentStepExecutor().execute(step, context)
                else -> error("This control expects only document lifecycle steps")
            }
        }
    }

    private companion object {
        const val DEFAULT_OPEN_VERSION = 1
        const val HIGH_OPEN_VERSION = 40
    }
}
