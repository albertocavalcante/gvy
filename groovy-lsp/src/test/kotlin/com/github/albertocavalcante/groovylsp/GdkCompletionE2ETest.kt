package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GdkCompletionE2ETest {

    private lateinit var server: GroovyLanguageServer
    private lateinit var mockClient: SynchronizingTestLanguageClient

    @BeforeEach
    fun setup() {
        server = GroovyLanguageServer()
        mockClient = SynchronizingTestLanguageClient()
        server.connect(mockClient)

        // Initialize server
        val params = InitializeParams().apply {
            processId = 1234
            workspaceFolders = listOf(WorkspaceFolder("file:///test/project", "test"))
            capabilities = ClientCapabilities()
            clientInfo = ClientInfo("Test Client", "1.0.0")
        }
        server.initialize(params).get()
    }

    @Test
    fun `test GDK completion via LSP`() = runBlocking {
        // Open a document with incomplete code
        val uri = "file:///test/GdkTest.groovy"
        val text = """
            def myList = [1, 2, 3]
            myList.
        """.trimIndent()

        val openParams = DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                this.uri = uri
                languageId = "groovy"
                version = 1
                this.text = text
            }
        }
        server.textDocumentService.didOpen(openParams)

        // Request completion after the dot
        val completionParams = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(1, 7) // Line 1 (0-indexed), char 7 (after "myList.")
        }

        val result = server.textDocumentService.completion(completionParams).get()

        assertNotNull(result)
        assertTrue(result.isLeft)
        val items = result.left

        // Verify GDK methods are present
        val labels = items.map { it.label }
        assertTrue(labels.contains("each"), "Should contain 'each'")
        assertTrue(labels.contains("find"), "Should contain 'find'")
        assertTrue(labels.contains("collect"), "Should contain 'collect'")
    }

    @Test
    fun `test Type Parameter completion via LSP`() = runBlocking {
        // Open a document with incomplete generic type
        val uri = "file:///test/TypeParamTest.groovy"
        val text = """
            List<Str
        """.trimIndent()

        val openParams = DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                this.uri = uri
                languageId = "groovy"
                version = 1
                this.text = text
            }
        }
        server.textDocumentService.didOpen(openParams)

        // Request completion after "Str"
        val completionParams = CompletionParams().apply {
            textDocument = TextDocumentIdentifier(uri)
            position = Position(0, 8) // Line 0, char 8 (after "List<Str")
        }

        val result = server.textDocumentService.completion(completionParams).get()

        assertNotNull(result)
        assertTrue(result.isLeft)
        val items = result.left

        // Verify String class is present
        val labels = items.map { it.label }
        assertTrue(labels.contains("String"), "Should contain 'String'")
    }
}
