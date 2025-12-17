package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Jenkins Shared Library vars/ directory go-to-definition support.
 *
 * Verifies that Ctrl+Click on a method call like `buildPlugin()` navigates to `vars/buildPlugin.groovy`.
 */
class JenkinsVarsDefinitionIntegrationTest {

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
    fun `should navigate to vars file from method call`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        val content = "node {\n    buildPlugin()\n}"

        openDocument(uri, content)
        // Position at 'buildPlugin' method name (line 1, char 4-14)
        val definitions = requestDefinitionAt(uri, Position(1, 6))

        assertNotNull(definitions, "Should find definitions for buildPlugin()")
        assertEquals(1, definitions.size, "Should have exactly one definition")
        assertTrue(
            definitions[0].uri.endsWith("vars/buildPlugin.groovy"),
            "Definition should point to vars/buildPlugin.groovy, but was: ${definitions[0].uri}",
        )
    }

    @Test
    fun `should navigate to different vars files`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        val content = "node {\n    deployApp()\n}"

        openDocument(uri, content)
        val definitions = requestDefinitionAt(uri, Position(1, 6))

        assertNotNull(definitions, "Should find definitions for deployApp()")
        assertEquals(1, definitions.size, "Should have exactly one definition")
        assertTrue(
            definitions[0].uri.endsWith("vars/deployApp.groovy"),
            "Definition should point to vars/deployApp.groovy, but was: ${definitions[0].uri}",
        )
    }

    @Test
    fun `should not find definition for unknown method`(): Unit = runBlocking {
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        val content = "node {\n    unknownMethod()\n}"

        openDocument(uri, content)
        val definitions = requestDefinitionAt(uri, Position(1, 6))

        // unknownMethod doesn't exist in vars/, so no definition should be found
        assertTrue(
            definitions.isEmpty(),
            "Should not find definition for unknownMethod()",
        )
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
        // Wait for diagnostics (and thus compilation) to be ready before proceeding.
        // This is necessary because definition() relies on the compilation ensured by awaitDiagnostics().
        (serverHandle!!.server.textDocumentService as GroovyTextDocumentService)
            .awaitDiagnostics(java.net.URI.create(uri))
    }

    private suspend fun requestDefinitionAt(uri: String, pos: Position): List<Location> {
        val params = DefinitionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = pos
        }
        val result = serverHandle!!.server.textDocumentService.definition(params).get()
        return if (result.isLeft) {
            result.left
        } else {
            result.right.map { Location(it.targetUri, it.targetRange) }
        }
    }
}
