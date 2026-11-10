package com.github.albertocavalcante.gvy.gls

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hover for Jenkins step parameters and global variables: they used to fall through to the generic
 * Groovy hover ("String literal", "def env") although the bundled metadata documents them (#1245).
 */
class JenkinsParameterHoverTest {

    private var serverHandle: TestLanguageServerHandle? = null

    @BeforeEach
    fun setup() {
        val runner = TestLanguageServerRunner()
        serverHandle = runner.startInMemoryServer()

        // Initialize the server with a workspace folder so Jenkins file detection works
        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file:///tmp/jenkins-test", "jenkins-test"))
            initializationOptions = mapOf("groovy.languageServer.engine" to "native")
        }
        serverHandle!!.server.initialize(initParams).get()
        serverHandle!!.server.initialized(InitializedParams())

        // Wait for server to be ready and quiescent (background tasks finished)
        serverHandle!!.client.awaitQuiescentStatus()
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    private var documents = 0

    private suspend fun hoverText(content: String, line: Int, character: Int, name: String = "Jenkinsfile"): String? {
        val uri = "file:///tmp/jenkins-test/hover${documents++}/$name"
        openDocument(uri, content)
        return requestHoverAt(uri, Position(line, character))?.contents?.right?.value
    }

    @Test
    fun `hover on a step's named argument shows the parameter documentation`(): Unit = runBlocking {
        val line = "    sh(script: 'make', returnStdout: true)"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("returnStdout") + 2)

        assertNotNull(text)
        assertTrue(text.contains("returnStdout") && text.contains("boolean"), "parameter name and type: $text")
        assertTrue(text.contains("return stdout"), "parameter description: $text")
        assertTrue(text.contains("`sh`"), "names the step: $text")
    }

    @Test
    fun `hover on a directive entry argument uses the directive's parameter`(): Unit = runBlocking {
        val line = "        string(name: 'X', trim: true)"
        val code = "pipeline {\n    agent any\n    parameters {\n$line\n    }\n}"
        val text = hoverText(code, line = 3, character = line.indexOf("trim") + 1)

        assertNotNull(text)
        assertTrue(text.contains("trim") && text.contains("whitespace"), "string parameter's trim: $text")
    }

    @Test
    fun `hover on a credentials binding argument uses the step's parameter`(): Unit = runBlocking {
        val line = "    withCredentials([string(credentialsId: 'token', variable: 'T')]) {"
        val text = hoverText("node {\n$line\n    }\n}", line = 1, character = line.indexOf("credentialsId") + 2)

        assertNotNull(text)
        assertTrue(text.contains("credentialsId") && text.contains("Secret text"), "credentials binding: $text")
    }

    @Test
    fun `hover on a Jenkins global variable shows its documentation`(): Unit = runBlocking {
        val line = "    echo env.BRANCH_NAME"
        val text = hoverText("node {\n$line\n}", line = 1, character = line.indexOf("env") + 1)

        assertNotNull(text)
        assertTrue(text.contains("env") && text.contains("Environment variables"), "env documentation: $text")
    }

    @Test
    fun `a local variable named like a global keeps the ordinary hover`(): Unit = runBlocking {
        val line = "    echo env"
        val text = hoverText("node {\n    def env = 'x'\n$line\n}", line = 2, character = line.indexOf("env") + 1)

        assertTrue(
            text == null || !text.contains("Environment variables available"),
            "local env is not Jenkins env: $text",
        )
    }

    private suspend fun openDocument(uri: String, content: String) {
        val textDoc = TextDocumentItem().apply {
            this.uri = uri
            languageId = "groovy"
            version = 1
            text = content
        }

        serverHandle!!.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = textDoc
            },
        )
    }

    private suspend fun requestHoverAt(uri: String, position: Position): Hover? {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            this.position = position
        }

        return serverHandle!!.server.textDocumentService.hover(params).get()
    }
}
