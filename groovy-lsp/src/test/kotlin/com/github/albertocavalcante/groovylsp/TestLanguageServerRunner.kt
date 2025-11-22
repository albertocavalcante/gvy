package com.github.albertocavalcante.groovylsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test harness for running LSP server in non-blocking mode for testing.
 * This allows integration testing without the server blocking the shell.
 */
class TestLanguageServerRunner {
    private val logger = LoggerFactory.getLogger(TestLanguageServerRunner::class.java)
    private var serverProcess: Process? = null
    private var launcher: Launcher<LanguageClient>? = null
    private var server: GroovyLanguageServer? = null
    private val executor = Executors.newCachedThreadPool()

    /**
     * Start the server in socket mode for testing.
     * Returns when the server is ready to accept connections.
     */
    suspend fun startSocketServer(port: Int = 8080): CompletableFuture<Unit> = withContext(Dispatchers.IO) {
        val future = CompletableFuture<Unit>()

        try {
            // Start server process in socket mode
            val processBuilder = ProcessBuilder(
                "java",
                "-jar",
                "build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar",
                "socket",
                port.toString(),
            )
            processBuilder.directory(File("."))
            processBuilder.redirectErrorStream(true)

            serverProcess = processBuilder.start()

            // Wait for server to be ready by checking if it's listening on the port
            waitForServerReady(port)

            future.complete(Unit)
        } catch (e: Exception) {
            future.completeExceptionally(e)
        }

        future
    }

    /**
     * Start the server in-memory for direct testing.
     * This creates a server instance directly without process overhead.
     */
    fun startInMemoryServer(): TestLanguageServerHandle {
        val testClient = SynchronizingTestLanguageClient()
        val server = GroovyLanguageServer()

        // Create in-memory pipes for communication
        val clientToServer = PipedOutputStream()
        val serverFromClient = PipedInputStream(clientToServer)

        val serverToClient = PipedOutputStream()

        @Suppress("UNUSED_VARIABLE")
        val clientFromServer = PipedInputStream(serverToClient)

        // Set up launcher
        val launcher = LSPLauncher.createServerLauncher(
            server,
            serverFromClient,
            serverToClient,
            executor,
        ) { it }

        // Connect client to server
        server.connect(testClient)

        // Start listening
        val listening = launcher.startListening()

        return TestLanguageServerHandle(server, testClient, launcher, listening)
    }

    /**
     * Stop any running server processes.
     */
    fun stop() {
        try {
            launcher?.let {
                // No direct way to stop launcher, but we can stop the server
            }
            serverProcess?.let { process ->
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        } catch (e: IOException) {
            logger.debug("Error during cleanup", e)
        } finally {
            serverProcess = null
            launcher = null
            server = null
        }
    }

    /**
     * Wait for the server to be ready by attempting to connect to the port.
     * This is more reliable than fixed delays.
     */
    private suspend fun waitForServerReady(port: Int, maxAttempts: Int = 50) {
        repeat(maxAttempts) { attempt ->
            try {
                Socket("localhost", port).use {
                    // Connection successful, server is ready
                    return
                }
            } catch (e: Exception) {
                if (attempt == maxAttempts - 1) {
                    throw IllegalStateException(
                        "Server did not start listening on port $port within ${maxAttempts * 100}ms",
                        e,
                    )
                }
                delay(100) // Short delay between connection attempts
            }
        }
    }
}

/**
 * Handle for managing an in-memory test server.
 */
data class TestLanguageServerHandle(
    val server: GroovyLanguageServer,
    val client: SynchronizingTestLanguageClient,
    val launcher: Launcher<LanguageClient>,
    val listening: java.util.concurrent.Future<Void>,
) {
    private val logger = LoggerFactory.getLogger(TestLanguageServerHandle::class.java)

    fun stop() {
        try {
            listening.cancel(true)
        } catch (e: CancellationException) {
            logger.debug("Future cancelled during stop", e)
        }
    }
}
