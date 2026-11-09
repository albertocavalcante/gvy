package com.github.albertocavalcante.gvy.gls

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Map-key completions for paren-less Jenkins steps must never edit the inside of a string literal. */
class JenkinsMapKeyStringContextTest {
    private var serverHandle: TestLanguageServerHandle? = null
    private val uri = "file:///tmp/jenkins-test/Jenkinsfile"

    @BeforeEach
    fun setup() {
        serverHandle = TestLanguageServerRunner().startInMemoryServer()
        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file:///tmp/jenkins-test", "jenkins-test"))
            initializationOptions = mapOf("groovy.languageServer.engine" to "native")
        }
        serverHandle!!.server.initialize(initParams).get()
        serverHandle!!.server.initialized(InitializedParams())
        serverHandle!!.client.awaitQuiescentStatus()
    }

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    @Test
    fun `cursor inside a closed string literal offers no map keys`(): Unit = runBlocking {
        val line = "  sh 'ls'"
        val items = complete("node {\n$line\n}", Position(1, line.length - 1))

        assertNull(items.find { it.label == "returnStdout:" }, "must not insert into the string literal")
    }

    @Test
    fun `cursor after a complete positional argument offers comma-prefixed map keys`(): Unit = runBlocking {
        val line = "  sh 'ls' "
        val items = complete("node {\n$line\n}", Position(1, line.length))

        val returnStdout = items.find { it.label == "returnStdout:" }
        assertNotNull(
            returnStdout,
            "returnStdout should be offered; got ${items.size} items: ${items.map {
                it.label
            }.take(15)}",
        )
        assertTrue(returnStdout.insertText.startsWith(", returnStdout: "), returnStdout.insertText)
    }

    @Test
    fun `parenthesised call keeps plain map-key insertion`(): Unit = runBlocking {
        val items = complete("node {\n  sh(\n    \n  )\n}", Position(2, 4))

        val returnStdout = items.find { it.label == "returnStdout:" }
        assertNotNull(returnStdout)
        assertEquals("returnStdout: ", returnStdout.insertText.substring(0, "returnStdout: ".length))
    }

    private suspend fun complete(content: String, position: Position): List<CompletionItem> {
        serverHandle!!.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem().apply {
                    uri = this@JenkinsMapKeyStringContextTest.uri
                    languageId = "groovy"
                    version = 1
                    text = content
                }
            },
        )
        val params = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            this.position = position
        }
        return serverHandle!!.server.textDocumentService.completion(params).get().left
    }
}
