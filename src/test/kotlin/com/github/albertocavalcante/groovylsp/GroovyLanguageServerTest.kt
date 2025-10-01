package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
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
    fun `test server initialization`() = runTest {
        val params = InitializeParams().apply {
            processId = 1234
            workspaceFolders = listOf(WorkspaceFolder("file:///test/project", "test"))
            initializationOptions = mapOf("groovy.codenarc.enabled" to false)
            capabilities = ClientCapabilities()
            clientInfo = ClientInfo("Test Client", "1.0.0")
        }

        val result = server.initialize(params).get()

        assertNotNull(result)
        assertNotNull(result.capabilities)
        assertEquals("Groovy Language Server", result.serverInfo?.name)
        assertEquals("0.1.0-SNAPSHOT", result.serverInfo?.version)

        // Check capabilities
        val capabilities = result.capabilities
        assertNotNull(capabilities.completionProvider)
        assertTrue(capabilities.completionProvider.triggerCharacters?.contains(".") == true)
        assertNotNull(capabilities.hoverProvider)
        assertNotNull(capabilities.definitionProvider)
    }

    @Test
    fun `test completion returns items`() = runTest {
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
    fun `test hover returns content`() = runTest {
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
    fun `test document open compiles valid groovy file`() = runTest {
        // Initialize server with CodeNarc disabled
        val initParams = InitializeParams().apply {
            processId = 1234
            workspaceFolders = listOf(WorkspaceFolder("file:///test/project", "test"))
            initializationOptions = mapOf("groovy.codenarc.enabled" to false)
            capabilities = ClientCapabilities()
            clientInfo = ClientInfo("Test Client", "1.0.0")
        }
        server.initialize(initParams).get()
        server.initialized(org.eclipse.lsp4j.InitializedParams())

        val params = DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                uri = "file:///test.groovy"
                languageId = "groovy"
                version = 1
                text = "class TestClass {\n    void hello() {\n        println 'Hello World'\n    }\n}"
            }
        }

        server.textDocumentService.didOpen(params)

        // Wait for compilation to complete and diagnostics to be published
        val publishedDiagnostics = mockClient.awaitSuccessfulCompilation("file:///test.groovy")

        assertEquals("file:///test.groovy", publishedDiagnostics.uri)
        // Valid Groovy code should have no errors (already verified by awaitSuccessfulCompilation)
        assertTrue(publishedDiagnostics.diagnostics.isEmpty())
    }

    @Test
    fun `test hover should work immediately after workspace initialization`() = runTest {
        // This test verifies that hover works immediately after server initialization
        // Previously this would fail because workspace compilation was async

        // Create initialization params for single-file compilation (no workspace)
        val params = InitializeParams().apply {
            processId = 1234
            // Remove workspaceFolders to use single-file compilation
            initializationOptions = mapOf("groovy.codenarc.enabled" to false)
            capabilities = ClientCapabilities()
            clientInfo = ClientInfo("Test Client", "1.0.0")
        }

        // Initialize server - this should BLOCK until everything is ready
        val initResult = server.initialize(params).get()
        assertNotNull(initResult)

        // Call initialized to complete the handshake
        server.initialized(org.eclipse.lsp4j.InitializedParams())

        // Use simple document content for single-file compilation
        val documentContent = "class TestClass { String name = \"test\" }"
        val documentUri = "file:///tmp/TestClass.groovy"
        val openParams = DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                uri = documentUri
                languageId = "groovy"
                version = 1
                text = documentContent
            }
        }

        server.textDocumentService.didOpen(openParams)

        // Wait for compilation to complete (indicated by diagnostic publication)
        mockClient.awaitDiagnostics(5000)

        // NOW test hover - this should work immediately!
        // Content: "class TestClass { String name = \"test\" }"
        // Hovering over "String" which starts at position 0:18
        val hoverParams = HoverParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(0, 18) // Position of "String" in "class TestClass { String name"
        }

        val hoverResult = server.textDocumentService.hover(hoverParams).get()

        // This should now PASS with the fix!
        assertNotNull(hoverResult, "Hover should work immediately after initialization")
        assertNotNull(hoverResult.contents, "Hover should have content")

        // Verify we got actual hover content, not the fallback message
        val content = hoverResult.contents.right.value
        // Should NOT contain the "No hover information available" fallback
        kotlin.test.assertFalse(
            content.contains("No hover information available"),
            "Should not show fallback message - actual hover should work. Got: $content",
        )

        // Should contain meaningful type information about String
        assertTrue(
            content.contains("String") || content.contains("type") || content.contains("class"),
            "Hover should contain type information about String. Got: $content",
        )
    }

    @Test
    fun `test hover should work after didChange`() = runTest {
        // Test that hover continues to work after document changes
        val params = InitializeParams().apply {
            processId = 1234
            // Remove workspaceFolders to use single-file compilation
            initializationOptions = mapOf("groovy.codenarc.enabled" to false)
            capabilities = ClientCapabilities()
            clientInfo = ClientInfo("Test Client", "1.0.0")
        }

        server.initialize(params).get()
        server.initialized(org.eclipse.lsp4j.InitializedParams())

        val documentUri = "file:///tmp/TestClass.groovy"
        val initialContent = "class TestClass { String name = \"test\" }"

        // Open document
        val openParams = DidOpenTextDocumentParams().apply {
            textDocument = TextDocumentItem().apply {
                uri = documentUri
                languageId = "groovy"
                version = 1
                text = initialContent
            }
        }
        server.textDocumentService.didOpen(openParams)

        // Wait for initial compilation to complete
        mockClient.awaitDiagnostics(5000)

        // Reset client to wait for the next diagnostic publication
        mockClient.reset()

        // Change document content
        val changedContent = "class TestClass { Integer age = 25; String name = \"test\" }"
        val changeParams = DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(documentUri, 2)
            contentChanges = listOf(
                TextDocumentContentChangeEvent(changedContent),
            )
        }
        server.textDocumentService.didChange(changeParams)

        // Wait for change processing to complete
        mockClient.awaitDiagnostics(5000)

        // Test hover on the new field "Integer"
        val hoverParams = HoverParams().apply {
            textDocument = TextDocumentIdentifier(documentUri)
            position = Position(0, 18) // Position of "Integer" in changed content
        }

        val hoverResult = server.textDocumentService.hover(hoverParams).get()

        assertNotNull(hoverResult, "Hover should work after didChange")
        assertNotNull(hoverResult.contents, "Hover should have content after didChange")

        val content = hoverResult.contents.right.value
        kotlin.test.assertFalse(
            content.contains("No hover information available"),
            "Should not show fallback message after didChange. Got: $content",
        )

        // Should contain information about Integer type
        assertTrue(
            content.contains("Integer") || content.contains("age") || content.contains("Property"),
            "Hover should contain information about the changed content. Got: $content",
        )
    }

    @Test
    fun `test shutdown and exit`() = runTest {
        val result = server.shutdown().get()
        assertNotNull(result)
        // Note: We don't actually call exit() in tests as it would terminate the test JVM
    }
}
