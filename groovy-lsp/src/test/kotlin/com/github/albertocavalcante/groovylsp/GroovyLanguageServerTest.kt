package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroovyLanguageServerTest {

    private lateinit var server: GroovyLanguageServer
    private lateinit var mockClient: SynchronizingTestLanguageClient

    @BeforeEach
    fun setup() {
        server = GroovyLanguageServer()
        mockClient = SynchronizingTestLanguageClient()
        server.connect(mockClient)
    }

    @Test
    fun `test server initialization`() {
        runBlocking {
            val params = InitializeParams().apply {
                processId = 1234
                workspaceFolders = listOf(WorkspaceFolder("file:///test/project", "test"))
                capabilities = ClientCapabilities()
                clientInfo = ClientInfo("Test Client", "1.0.0")
            }

            val result = server.initialize(params).get()

            assertNotNull(result)
            assertNotNull(result.capabilities)
            assertEquals("Groovy Language Server", result.serverInfo?.name)
            assertEquals(Version.current, result.serverInfo?.version)

            // Check capabilities
            val capabilities = result.capabilities
            assertNotNull(capabilities.completionProvider)
            assertTrue(capabilities.completionProvider.triggerCharacters?.contains(".") == true)
            assertNotNull(capabilities.hoverProvider)
            assertNotNull(capabilities.definitionProvider)
            assertTrue(capabilities.documentFormattingProvider?.left == true)
        }
    }

    @Test
    fun `test completion returns items`() = runBlocking {
        val params = CompletionParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            position = Position(0, 0)
        }

        val result = server.textDocumentService.completion(params).get()

        assertNotNull(result)
        assertTrue(result.isLeft)

        val items = result.left
        assertTrue(items.isNotEmpty())
        assertTrue(items.any { it.label == "println" })
        assertTrue(items.any { it.label == "def" })
        assertTrue(items.any { it.label == "class" })
    }

    @Test
    fun `test hover returns content`() = runBlocking {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier("file:///test.groovy")
            position = Position(5, 10)
        }

        val result = server.textDocumentService.hover(params).get()

        assertNotNull(result)
        assertNotNull(result.contents)
        assertTrue(result.contents.isRight)

        val content = result.contents.right
        assertEquals(MarkupKind.MARKDOWN, content.kind)
        // The new hover implementation returns "No information available" when no AST node is found
        assertTrue(content.value.contains("No information available") || content.value.contains("groovy"))
    }

    @Test
    fun `test document open compiles valid groovy file`() = runBlocking {
        val params = DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                uri = "file:///TestClass.groovy"
                languageId = "groovy"
                version = 1
                text = "class TestClass {\n    void hello() {\n        println 'Hello World'\n    }\n}"
            }
        }

        // Initialize server first
        val initParams = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder("file:///test/project", "project"))
            capabilities = ClientCapabilities()
        }
        server.initialize(initParams).get()
        server.initialized(org.eclipse.lsp4j.InitializedParams())

        server.textDocumentService.didOpen(params)

        // Wait for compilation to complete and diagnostics to be published
        val publishedDiagnostics = mockClient.awaitSuccessfulCompilation("file:///TestClass.groovy")

        assertEquals("file:///TestClass.groovy", publishedDiagnostics.uri)
        // Valid Groovy code should have no errors (already verified by awaitSuccessfulCompilation)
        assertTrue(publishedDiagnostics.diagnostics.isEmpty())
    }

    @Test
    fun `test shutdown and exit`() {
        runBlocking {
            val result = server.shutdown().get()
            assertNotNull(result)
            // Note: We don't actually call exit() in tests as it would terminate the test JVM
        }
    }
}
