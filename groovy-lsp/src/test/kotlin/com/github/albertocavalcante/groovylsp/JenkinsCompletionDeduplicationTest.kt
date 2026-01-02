package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue

class JenkinsCompletionDeduplicationTest {

    private var serverHandle: TestLanguageServerHandle? = null

    @BeforeEach
    fun setup() {
        val runner = TestLanguageServerRunner()
        serverHandle = runner.startInMemoryServer()

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
    fun `options block should not have duplicate completions`() = runBlocking {
        val uri = "file:///tmp/jenkins-test/Jenkinsfile"
        val content = """
            pipeline {
              agent any
              options {
                
              }
            }
        """.trimIndent()

        openDocument(uri, content)

        val items = requestCompletionsAt(uri, Position(3, 4))
        val labels = items.map { it.label }

        val disableConcurrentBuildsCount = labels.count { it == "disableConcurrentBuilds" }
        assertEquals(
            1,
            disableConcurrentBuildsCount,
            "Should have exactly one completion for disableConcurrentBuilds, found: ${labels.filter {
                it == "disableConcurrentBuilds"
            }.size}",
        )

        assertTrue(labels.contains("timestamps"), "Should suggest declarative options")
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
