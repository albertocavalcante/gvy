package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItemKind
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JenkinsBundledCompletionTest {

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
        serverHandle!!.server.initialized(org.eclipse.lsp4j.InitializedParams())
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    @Test
    fun `jenkinsfile should include bundled jenkins step completions`() = runBlocking {
        val uri = "file:///tmp/jenkins-test/Jenkinsfile"
        val content = """
            pipeline {
              agent any
              stages {
                stage('Build') {
                  steps {
                    
                  }
                }
              }
            }
        """.trimIndent()

        openDocument(uri, content)

        // Position inside the empty steps block
        val items = requestCompletionsAt(uri, Position(6, 8))

        val sh = items.find { it.label == "sh" }
        assertNotNull(sh, "Bundled Jenkins steps should surface 'sh' completion")
        assertEquals(CompletionItemKind.Function, sh.kind)
        assertTrue(sh.detail?.contains("workflow-durable-task-step") == true)
    }

    @Test
    fun `jenkinsfile should suggest map keys for bundled steps`() = runBlocking {
        val uri = "file:///tmp/jenkins-test/Jenkinsfile"
        val content = """
            node {
              sh(
                
              )
            }
        """.trimIndent()

        openDocument(uri, content)

        // Position inside the sh map literal area
        val items = requestCompletionsAt(uri, Position(2, 4))

        val returnStdout = items.find { it.label == "returnStdout:" }
        assertNotNull(returnStdout, "Should suggest returnStdout map key for sh")
        assertEquals(CompletionItemKind.Property, returnStdout.kind)
    }

    @Test
    fun `jenkinsfile should provide rich hover for Jenkins steps`() = runBlocking {
        val uri = "file:///tmp/jenkins-test/Jenkinsfile"
        val content = """
            stage('Build') {
                echo 'Building...'
                sh 'make build'
            }
        """.trimIndent()

        openDocument(uri, content)

        // Hover on 'stage' step - line 0, character 0
        val stageHover = requestHoverAt(uri, Position(0, 2))
        assertNotNull(stageHover, "Should have hover for 'stage' step")

        val stageContent = stageHover.contents.right.value
        assertTrue(
            stageContent.contains("Jenkins Step"),
            "Stage hover should show 'Jenkins Step' header. Got: $stageContent",
        )
        assertTrue(
            stageContent.contains("stage"),
            "Stage hover should contain step name. Got: $stageContent",
        )

        // Hover on 'echo' step - line 1
        val echoHover = requestHoverAt(uri, Position(1, 6))
        assertNotNull(echoHover, "Should have hover for 'echo' step")

        val echoContent = echoHover.contents.right.value
        assertTrue(
            echoContent.contains("Jenkins Step") && echoContent.contains("echo"),
            "Echo hover should show Jenkins step documentation. Got: $echoContent",
        )

        // Hover on 'sh' step - line 2
        val shHover = requestHoverAt(uri, Position(2, 6))
        assertNotNull(shHover, "Should have hover for 'sh' step")

        val shContent = shHover.contents.right.value
        assertTrue(
            shContent.contains("Jenkins Step") && shContent.contains("sh"),
            "Sh hover should show Jenkins step documentation. Got: $shContent",
        )
        assertTrue(
            shContent.contains("script"),
            "Sh hover should include script parameter. Got: $shContent",
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
            org.eclipse.lsp4j.DidOpenTextDocumentParams().apply {
                textDocument = textDoc
            },
        )
    }

    private suspend fun requestCompletionsAt(uri: String, position: Position): List<org.eclipse.lsp4j.CompletionItem> {
        val params = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            this.position = position
        }

        val result = serverHandle!!.server.textDocumentService.completion(params).get()
        return result.left
    }

    private suspend fun requestHoverAt(uri: String, position: Position): org.eclipse.lsp4j.Hover? {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            this.position = position
        }

        return serverHandle!!.server.textDocumentService.hover(params).get()
    }
}
