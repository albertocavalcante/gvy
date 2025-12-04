package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
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
}
