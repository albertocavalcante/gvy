package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull

/**
 * Integration tests for Jenkins Shared Library vars/ directory support.
 *
 * Uses fixtures from test resources for realistic workspace structure.
 * Prevents regressions in vars/ completion and hover functionality.
 */
class JenkinsVarsCompletionIntegrationTest {

    companion object {
        private const val FIXTURE_PATH = "fixtures/jenkins-shared-library"
    }

    private var serverHandle: TestLanguageServerHandle? = null
    private lateinit var workspaceRoot: Path

    @BeforeEach
    fun setup() {
        workspaceRoot = copyFixtureToTempDir()

        val runner = TestLanguageServerRunner()
        serverHandle = runner.startInMemoryServer()

        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "test"))
        }
        serverHandle!!.server.initialize(initParams).get()
        serverHandle!!.server.initialized(org.eclipse.lsp4j.InitializedParams())
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
        workspaceRoot.toFile().deleteRecursively()
    }

    /**
     * Copy fixture resources to a temp directory for test isolation.
     */
    private fun copyFixtureToTempDir(): Path {
        val tempDir = Files.createTempDirectory("jenkins-shared-lib-test")

        val fixtureUrl = javaClass.classLoader.getResource(FIXTURE_PATH)
            ?: error("Fixture not found: $FIXTURE_PATH")

        val fixturePath = Path.of(fixtureUrl.toURI())
        fixturePath.toFile().copyRecursively(tempDir.toFile())

        return tempDir
    }

    @Test
    fun `should complete vars global variables in Jenkinsfile`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        val content = "pipeline { stages { stage('Build') { steps {\n\n} } } }"

        openDocument(uri, content)
        val items = requestCompletionsAt(uri, Position(1, 0))

        assertNotNull(items.find { it.label == "buildPlugin" }, "Should complete 'buildPlugin' from vars/")
        assertNotNull(items.find { it.label == "deployApp" }, "Should complete 'deployApp' from vars/")
        assertNotNull(items.find { it.label == "infra" }, "Should complete 'infra' from vars/ (no .txt)")
    }

    @Test
    fun `vars completions should include documentation from txt files`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        openDocument(uri, "node {\n\n}")

        val items = requestCompletionsAt(uri, Position(1, 0))
        val buildPlugin = items.find { it.label == "buildPlugin" }

        assertNotNull(buildPlugin, "Should find buildPlugin completion")

        val doc = buildPlugin.documentation?.let {
            when {
                it.isRight -> it.right.value
                it.isLeft -> it.left
                else -> null
            }
        }
        assertNotNull(doc, "buildPlugin should have documentation")
    }

    @Test
    fun `vars without txt files should still complete`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        openDocument(uri, "node {\n\n}")

        val items = requestCompletionsAt(uri, Position(1, 0))
        assertNotNull(items.find { it.label == "infra" }, "Should complete 'infra' even without .txt")
    }

    @Test
    fun `should show hover documentation for vars calls`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        openDocument(uri, "node {\n    buildPlugin()\n}")

        val hover = requestHoverAt(uri, Position(1, 8))
        assertNotNull(hover, "Should have hover for buildPlugin()")
    }

    @Test
    fun `vars completions should coexist with bundled Jenkins steps`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        openDocument(uri, "pipeline { steps {\n\n} }")

        val items = requestCompletionsAt(uri, Position(1, 0))

        assertNotNull(items.find { it.label == "buildPlugin" }, "Should have vars/: buildPlugin")
        assertNotNull(items.find { it.label == "sh" }, "Should have bundled step: sh")
        assertNotNull(items.find { it.label == "echo" }, "Should have bundled step: echo")
    }

    @Test
    fun `empty vars directory should not break completions`(): Unit = runBlocking {
        workspaceRoot.resolve("vars").toFile().listFiles()?.forEach { it.delete() }

        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        openDocument(uri, "node {\n\n}")

        val items = requestCompletionsAt(uri, Position(1, 0))
        assertNotNull(items.find { it.label == "sh" }, "Bundled step 'sh' should work with empty vars/")
    }

    private suspend fun openDocument(uri: String, content: String) {
        serverHandle!!.server.textDocumentService.didOpen(
            org.eclipse.lsp4j.DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem().apply {
                    this.uri = uri
                    languageId = "groovy"
                    version = 1
                    text = content
                }
            },
        )
    }

    private suspend fun requestCompletionsAt(uri: String, pos: Position): List<org.eclipse.lsp4j.CompletionItem> {
        val params = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = pos
        }
        return serverHandle!!.server.textDocumentService.completion(params).get().left
    }

    private suspend fun requestHoverAt(uri: String, pos: Position): org.eclipse.lsp4j.Hover? {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = pos
        }
        return serverHandle!!.server.textDocumentService.hover(params).get()
    }
}
