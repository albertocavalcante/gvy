package com.github.albertocavalcante.groovylsp

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Test language client that provides proper synchronization instead of flaky delays.
 * This allows tests to wait for specific events rather than guessing timing.
 */
class SynchronizingTestLanguageClient : LanguageClient {

    // Storage for events
    private val diagnosticsRef = AtomicReference<PublishDiagnosticsParams?>()
    private val messagesRef = AtomicReference<MutableList<MessageParams>>(mutableListOf())

    // Synchronization primitives
    private var diagnosticsLatch = CountDownLatch(1)
    private var messagesLatch = CountDownLatch(1)

    // Timeout for operations (5 seconds should be enough for any compilation)
    private val timeoutMs = 5000L

    /**
     * Published diagnostics (may be null if none published yet)
     */
    val diagnostics: PublishDiagnosticsParams?
        get() = diagnosticsRef.get()

    /**
     * All messages received so far
     */
    val messages: List<MessageParams>
        get() = messagesRef.get().toList()

    override fun telemetryEvent(obj: Any?) {
        // No-op for tests
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        diagnosticsRef.set(diagnostics)
        diagnosticsLatch.countDown()
    }

    override fun showMessage(messageParams: MessageParams) {
        messagesRef.get().add(messageParams)
        messagesLatch.countDown()
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(MessageActionItem())

    override fun logMessage(message: MessageParams) {
        // Store log messages as regular messages for test verification
        messagesRef.get().add(message)
    }

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> =
        CompletableFuture.completedFuture(emptyList())

    /**
     * Wait for diagnostics to be published.
     * This is the main replacement for delay() in tests.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return The published diagnostics
     * @throws TimeoutException if diagnostics not published within timeout
     * @throws IllegalStateException if no diagnostics were actually published
     */
    fun awaitDiagnostics(timeoutMs: Long = this.timeoutMs): PublishDiagnosticsParams {
        if (!diagnosticsLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("Diagnostics not published within ${timeoutMs}ms")
        }
        return diagnosticsRef.get()
            ?: error("Diagnostics latch was released but no diagnostics found")
    }

    /**
     * Wait for at least one message to be received.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return The first message received
     * @throws TimeoutException if no message received within timeout
     */
    fun awaitMessage(timeoutMs: Long = this.timeoutMs): MessageParams {
        if (!messagesLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("No message received within ${timeoutMs}ms")
        }
        val messagesList = messagesRef.get()
        if (messagesList.isEmpty()) {
            error("Message latch was released but no messages found")
        }
        return messagesList.first()
    }

    /**
     * Wait for diagnostics to be published for a specific URI.
     * Useful when multiple files might be compiled.
     *
     * @param expectedUri The URI we're waiting for diagnostics on
     * @param timeoutMs Maximum time to wait
     * @return The diagnostics for the expected URI
     * @throws TimeoutException if diagnostics for the URI not published within timeout
     */
    fun awaitDiagnosticsForUri(expectedUri: String, timeoutMs: Long = this.timeoutMs): PublishDiagnosticsParams {
        val diagnostics = awaitDiagnostics(timeoutMs)
        if (diagnostics.uri != expectedUri) {
            error("Expected diagnostics for URI '$expectedUri' but got '${diagnostics.uri}'")
        }
        return diagnostics
    }

    /**
     * Reset the client for reuse in another test.
     * This clears all stored data and resets synchronization primitives.
     */
    fun reset() {
        diagnosticsRef.set(null)
        messagesRef.set(mutableListOf())
        diagnosticsLatch = CountDownLatch(1)
        messagesLatch = CountDownLatch(1)
    }

    /**
     * Check if diagnostics have been published (non-blocking)
     */
    fun hasDiagnostics(): Boolean = diagnosticsRef.get() != null

    /**
     * Check if any messages have been received (non-blocking)
     */
    fun hasMessages(): Boolean = messagesRef.get().isNotEmpty()

    /**
     * Wait for diagnostics and verify they are empty (successful compilation)
     */
    fun awaitSuccessfulCompilation(uri: String, timeoutMs: Long = this.timeoutMs): PublishDiagnosticsParams {
        val diagnostics = awaitDiagnosticsForUri(uri, timeoutMs)
        if (diagnostics.diagnostics.isNotEmpty()) {
            val errors = diagnostics.diagnostics.joinToString("\n") {
                "Line ${it.range.start.line}: ${it.message}"
            }
            throw AssertionError("Expected successful compilation but got errors:\n$errors")
        }
        return diagnostics
    }

    /**
     * Wait for diagnostics and verify they contain errors (failed compilation)
     */
    fun awaitFailedCompilation(uri: String, timeoutMs: Long = this.timeoutMs): PublishDiagnosticsParams {
        val diagnostics = awaitDiagnosticsForUri(uri, timeoutMs)
        if (diagnostics.diagnostics.isEmpty()) {
            throw AssertionError("Expected compilation errors but compilation succeeded")
        }
        return diagnostics
    }
}
