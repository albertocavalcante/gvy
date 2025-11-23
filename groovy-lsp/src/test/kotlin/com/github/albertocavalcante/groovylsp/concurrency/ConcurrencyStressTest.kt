package com.github.albertocavalcante.groovylsp.concurrency

import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import com.github.albertocavalcante.groovylsp.TestLanguageServerHandle
import com.github.albertocavalcante.groovylsp.TestLanguageServerRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Stress test for checking race conditions and concurrency issues.
 * Simulates multiple clients or rapid-fire events to the server.
 */
class ConcurrencyStressTest {

    private var serverHandle: TestLanguageServerHandle? = null

    @AfterEach
    fun cleanup() {
        serverHandle?.stop()
    }

    @Test
    fun `test rapid concurrent modifications and completion requests`() {
        runBlocking {
            val runner = TestLanguageServerRunner()
            serverHandle = runner.startInMemoryServer()
            val server = serverHandle!!.server
            val client = serverHandle!!.client

            // Initialize
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///tmp/test", "test"))
                },
            ).get()

            val uri = "file:///Concurrent.groovy"
            val code = """
                class Concurrent {
                    String value = "initial"
                    void process() {
                        println value
                    }
                }
            """.trimIndent()

            // Open document
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams().apply {
                    textDocument = TextDocumentItem(uri, "groovy", 1, code)
                },
            )

            client.awaitSuccessfulCompilation(uri)

            // Launch concurrent jobs
            val duration = 5000L
            val startTime = System.currentTimeMillis()

            coroutineScope {
                val writer = async(Dispatchers.Default) {
                    runWriter(server, uri, startTime, duration)
                }

                val readers = (1..5).map { id ->
                    async(Dispatchers.Default) {
                        runReader(server, uri, startTime, duration, id)
                    }
                }

                awaitAll(writer, *readers.toTypedArray())
            }

            server.shutdown().get()
        }
    }

    private suspend fun runWriter(server: GroovyLanguageServer, uri: String, startTime: Long, duration: Long) {
        var version = 2
        while (System.currentTimeMillis() - startTime < duration) {
            val newText = """
                class Concurrent {
                    String value = "v$version"
                    void process() {
                        println value
                    }
                }
            """.trimIndent()

            server.textDocumentService.didChange(
                DidChangeTextDocumentParams().apply {
                    textDocument = VersionedTextDocumentIdentifier(uri, version++)
                    contentChanges = listOf(TextDocumentContentChangeEvent(newText))
                },
            )
            delay(Random.nextLong(10, 50))
        }
    }

    private suspend fun runReader(
        server: GroovyLanguageServer,
        uri: String,
        startTime: Long,
        duration: Long,
        id: Int,
    ): Int {
        var requests = 0
        while (System.currentTimeMillis() - startTime < duration) {
            try {
                val result = server.textDocumentService.completion(
                    CompletionParams().apply {
                        textDocument = TextDocumentIdentifier(uri)
                        position = Position(3, 20) // After "println value"
                    },
                ).get()

                // Just ensure we get a result and no exception thrown
                if (result.isLeft) {
                    assertTrue(result.left.isNotEmpty(), "Reader $id got empty completion list")
                }
                requests++
                delay(Random.nextLong(10, 30))
            } catch (e: Exception) {
                throw IllegalStateException("Reader $id failed", e)
            }
        }
        return requests
    }
}
