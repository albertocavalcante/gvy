// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Protocol evidence stays separate from VS Code's independently contributed snippets. */
class JenkinsAuthoringBaselineTest {
    @TempDir
    lateinit var workspace: Path

    private val server = GroovyLanguageServer(exitProcess = {})
    private val client = SynchronizingTestLanguageClient()
    private val executor = Executors.newCachedThreadPool()
    private val clientInput = PipedInputStream()
    private val serverOutput = RecordingOutputStream(PipedOutputStream(clientInput))
    private val serverInput = PipedInputStream()
    private val clientOutput = RecordingOutputStream(PipedOutputStream(serverInput))
    private lateinit var remote: LanguageServer
    private lateinit var serverListening: Future<Void>
    private lateinit var clientListening: Future<Void>

    @BeforeEach
    fun startServer() {
        server.connect(client)
        val serverLauncher = LSPLauncher.createServerLauncher(server, serverInput, serverOutput, executor) { it }
        val clientLauncher = LSPLauncher.createClientLauncher(client, clientInput, clientOutput, executor) { it }
        serverListening = serverLauncher.startListening()
        clientListening = clientLauncher.startListening()
        remote = clientLauncher.remoteProxy
        remote.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "authoring"))
                initializationOptions = mapOf("groovy.languageServer.engine" to "native")
            },
        ).get(30, TimeUnit.SECONDS)
        remote.initialized(InitializedParams())
        client.awaitQuiescentStatus()
    }

    @AfterEach
    fun stopServer() {
        try {
            server.shutdown().get(30, TimeUnit.SECONDS)
        } finally {
            if (::serverListening.isInitialized) serverListening.cancel(true)
            if (::clientListening.isInitialized) clientListening.cancel(true)
            clientOutput.close()
            serverOutput.close()
            clientInput.close()
            serverInput.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `blank parameter fixture returns exactly the bundled definition names`() {
        assertDefinitionNames(completeFixture("parameters-blank"))
    }

    @Test
    fun `partial parameter fixture keeps the server catalogue free of editor snippets`() {
        assertDefinitionNames(completeFixture("parameters-partial"))
    }

    @Test
    fun `argument fixture captures the complete unfiltered protocol response`() {
        val items = completeFixture("parameters-arguments")
        val labels = items.map { it.label }
        assertTrue(labels.containsAll(listOf("name:", "defaultValue:", "description:", "trim:")))
        // G06 remains open: the capture includes every kind, including any leaked expressions.
        assertEquals(labels.size, labels.toSet().size, "Duplicate labels in full argument response: $labels")
    }

    private fun assertDefinitionNames(items: List<CompletionItem>) {
        assertEquals(
            listOf("booleanParam", "choice", "password", "string", "text"),
            items.map { it.label }.sorted(),
        )
    }

    private fun completeFixture(name: String): List<CompletionItem> {
        val resource = javaClass.getResource("/fixtures/jenkins-declarative/$name.Jenkinsfile")
        val marked = assertNotNull(resource).readText()
        val marker = "/*caret*/"
        val offset = marked.indexOf(marker)
        require(offset >= 0 && marked.indexOf(marker, offset + marker.length) == -1)
        val content = marked.replace(marker, "")
        val prefix = content.take(offset)
        val position = Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length)
        val uri = workspace.resolve("$name/Jenkinsfile").toUri().toString()
        remote.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "jenkinsfile", 1, content)),
        )
        client.awaitQuiescentStatus()
        val result = remote.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(uri), position),
        ).get(30, TimeUnit.SECONDS)
        System.getenv("GVY_JENKINS_EVIDENCE_DIR")?.let { directory ->
            val evidence = Path.of(directory)
            require(evidence.isAbsolute && Files.isDirectory(evidence))
            Files.write(evidence.resolve("server-$name.lsp"), serverOutput.snapshot())
            Files.write(evidence.resolve("client-$name.lsp"), clientOutput.snapshot())
        }
        return if (result.isLeft) result.left else result.right.items
    }

    /** Retains the actual framed bytes, including request IDs assigned by the transport. */
    private class RecordingOutputStream(private val downstream: OutputStream) : OutputStream() {
        private val recorded = ByteArrayOutputStream()

        @Synchronized
        override fun write(value: Int) {
            downstream.write(value)
            recorded.write(value)
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            downstream.write(bytes, offset, length)
            recorded.write(bytes, offset, length)
        }

        override fun flush() = downstream.flush()

        override fun close() = downstream.close()

        @Synchronized
        fun snapshot(): ByteArray = recorded.toByteArray()
    }
}
