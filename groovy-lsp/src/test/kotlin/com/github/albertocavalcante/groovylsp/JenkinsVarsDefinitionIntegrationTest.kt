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

    /**
     * FIXME: src/ class resolution is partially implemented - infrastructure is in place (src/ in source roots,
     * class name matching for simple/fully-qualified names) but the cursor-to-AST-node resolution doesn't find
     * the ConstructorCallExpression at the expected position. Needs more investigation.
     */
    @org.junit.jupiter.api.Disabled("FIXME: src/ class resolution - cursor position/AST node type needs investigation")
    @Test
    fun `should navigate to class definition in src directory`(): Unit = runBlocking {
        // The buildPlugin.groovy fixture already imports org.example.BuildHelper from src/
        val uri = workspaceRoot.resolve("Jenkinsfile").toUri().toString()
        // Import the class and use it
        val content = """
            import org.example.BuildHelper
            
            node {
                def helper = new BuildHelper()
                helper.runBuild()
            }
        """.trimIndent()

        openDocument(uri, content)
        // Open the src file too so it gets compiled
        val srcUri = workspaceRoot.resolve("src/org/example/BuildHelper.groovy").toUri().toString()
        openDocument(srcUri, Files.readString(workspaceRoot.resolve("src/org/example/BuildHelper.groovy")))

        // Position at 'BuildHelper' in "new BuildHelper()" on line 3 (0-indexed)
        // "    def helper = new BuildHelper()"
        // Position 21 should hit "BuildHelper" after "new "
        val definitions = requestDefinitionAt(uri, Position(3, 21))

        assertNotNull(definitions, "Should find definitions for BuildHelper")
        assertTrue(
            definitions.isNotEmpty(),
            "Should have at least one definition for BuildHelper (definitions: ${definitions.size})",
        )
        assertTrue(
            definitions.any { it.uri.contains("src/org/example/BuildHelper.groovy") },
            "Definition should point to src/org/example/BuildHelper.groovy, but was: ${definitions.map { it.uri }}",
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
