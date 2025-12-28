package com.github.albertocavalcante.groovylsp

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Test language client that provides proper synchronization instead of flaky delays.
 * This allows tests to wait for specific events rather than guessing timing.
 *
 * Uses per-URI queues to support concurrent tests without race conditions.
 */
class SynchronizingTestLanguageClient : LanguageClient {

    // Per-URI diagnostics queues for thread-safe, order-preserving storage
    private val diagnosticsQueues = ConcurrentHashMap<String, LinkedBlockingQueue<PublishDiagnosticsParams>>()

    // Legacy single-value storage for backwards compatibility with simple await methods
    private val diagnosticsRef = AtomicReference<PublishDiagnosticsParams?>()
    private val messagesRef = AtomicReference<MutableList<MessageParams>>(mutableListOf())

    private val messagesQueue = LinkedBlockingQueue<MessageParams>()

    // Synchronization primitives (legacy, kept for simple await methods)
    private var diagnosticsLatch = CountDownLatch(1)
    private var messagesLatch = CountDownLatch(1)

    // Timeout for operations (30 seconds to avoid flakiness in CI/parallel runs)
    private val timeoutMs = 30000L

    /**
     * Published diagnostics (may be null if none published yet)
     * Note: In parallel scenarios, use awaitDiagnosticsForUri instead.
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
        // Store in per-URI queue for reliable multi-file testing
        // Skip null URIs as ConcurrentHashMap doesn't allow null keys
        if (diagnostics.uri != null) {
            diagnosticsQueues.computeIfAbsent(diagnostics.uri) { LinkedBlockingQueue() }
                .offer(diagnostics)
        }

        // Also update legacy storage for backwards compatibility
        diagnosticsRef.set(diagnostics)
        diagnosticsLatch.countDown()
    }

    override fun showMessage(messageParams: MessageParams) {
        messagesRef.get().add(messageParams)
        messagesQueue.offer(messageParams)
        messagesLatch.countDown()
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(MessageActionItem())

    override fun logMessage(message: MessageParams) {
        // Store log messages as regular messages for test verification
        messagesRef.get().add(message)
    }

    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> =
        CompletableFuture.completedFuture(null)

    override fun notifyProgress(params: ProgressParams) {
        // Progress updates are ignored for tests
    }

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> =
        CompletableFuture.completedFuture(emptyList())

    /**
     * Wait for diagnostics to be published (any URI).
     * Note: In parallel test scenarios, prefer awaitDiagnosticsForUri.
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
    fun awaitMessage(timeoutMs: Long = this.timeoutMs): MessageParams =
        messagesQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw TimeoutException("No message received within ${timeoutMs}ms")

    fun awaitMessageMatching(timeoutMs: Long = this.timeoutMs, predicate: (MessageParams) -> Boolean): MessageParams {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
            val msg = messagesQueue.poll(remainingMs, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(msg)) return msg
        }

        throw TimeoutException(
            "No matching message received within ${timeoutMs}ms. Messages: ${messagesRef.get().map { it.message }}",
        )
    }

    /**
     * Wait for diagnostics to be published for a specific URI.
     * This is the preferred method for multi-file or parallel test scenarios.
     *
     * Uses per-URI queues to avoid race conditions when multiple files are compiled.
     *
     * @param expectedUri The URI we're waiting for diagnostics on
     * @param timeoutMs Maximum time to wait
     * @return The diagnostics for the expected URI
     * @throws TimeoutException if diagnostics for the URI not published within timeout
     */
    fun awaitDiagnosticsForUri(expectedUri: String, timeoutMs: Long = this.timeoutMs): PublishDiagnosticsParams {
        val queue = diagnosticsQueues.computeIfAbsent(expectedUri) { LinkedBlockingQueue() }
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw TimeoutException("Diagnostics for URI '$expectedUri' not published within ${timeoutMs}ms")
    }

    /**
     * Reset the client for reuse in another test.
     * This clears all stored data and resets synchronization primitives.
     */
    fun reset() {
        diagnosticsRef.set(null)
        diagnosticsQueues.clear()
        messagesRef.set(mutableListOf())
        diagnosticsLatch = CountDownLatch(1)
        messagesLatch = CountDownLatch(1)
        messagesQueue.clear()
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
