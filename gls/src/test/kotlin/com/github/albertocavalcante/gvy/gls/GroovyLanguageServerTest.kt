// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroovyLanguageServerTest {

    @TempDir
    lateinit var workspace: Path

    private lateinit var server: GroovyLanguageServer
    private lateinit var mockClient: SynchronizingTestLanguageClient

    @BeforeEach
    fun setup() {
        server = GroovyLanguageServer()
        mockClient = SynchronizingTestLanguageClient()
        server.connect(mockClient)
    }

    @AfterEach
    fun cleanup() {
        server.shutdown().get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `test server initialization`() {
        runBlocking {
            val params = InitializeParams().apply {
                // No parent pid: a real watchdog on a made-up pid could kill the test JVM
                processId = null
                workspaceFolders = listOf(WorkspaceFolder("file:///test/project", "test"))
                capabilities = ClientCapabilities()
                clientInfo = ClientInfo("Test Client", "1.0.0")
            }

            val result = server.initialize(params).get()

            assertNotNull(result)
            assertNotNull(result.capabilities)
            assertEquals("Groovy Language Server", result.serverInfo?.name)
            assertEquals(Version.display, result.serverInfo?.version)
            if (Version.info.isStamped && !Version.info.release) {
                // A development jar must be traceable to its commit from the client's side of the protocol.
                // Release builds report the bare base version by design.
                assertTrue(result.serverInfo?.version?.contains(Version.info.gitShaShort) == true)
            }

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
    fun `test completion returns items`(): Unit = runBlocking {
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
    fun `hover for a missing document returns quiet markdown`(): Unit = runBlocking {
        val params = HoverParams().apply {
            textDocument = TextDocumentIdentifier(workspace.resolve("Missing.groovy").toUri().toString())
            position = Position(5, 10)
        }

        val result = server.textDocumentService.hover(params).get()

        assertNotNull(result)
        assertNotNull(result.contents)
        assertTrue(result.contents.isRight)

        val content = result.contents.right
        assertEquals(MarkupKind.MARKDOWN, content.kind)
        assertTrue(content.value.isBlank(), content.value)
    }

    @Test
    fun `default server hover includes the compiled symbol type`(): Unit = runBlocking {
        server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "hover"))
                capabilities = ClientCapabilities()
            },
        ).get(30, TimeUnit.SECONDS)
        server.initialized(InitializedParams())

        val uri = workspace.resolve("Inference.groovy").toUri().toString()
        server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "def count = 42")),
        )
        mockClient.awaitSuccessfulCompilation(uri)

        val result = assertNotNull(
            server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 5)))
                .get(30, TimeUnit.SECONDS),
        )

        assertTrue(result.contents.isRight)
        val content = assertNotNull(result.contents.right)
        assertEquals(MarkupKind.MARKDOWN, content.kind)
        assertContains(content.value, "int count")
    }

    @Test
    fun `test document open compiles valid groovy file`(): Unit = runBlocking {
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
        server.initialized(InitializedParams())

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
