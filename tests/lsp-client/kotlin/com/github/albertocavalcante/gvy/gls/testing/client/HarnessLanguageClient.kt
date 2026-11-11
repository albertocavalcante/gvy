package com.github.albertocavalcante.gvy.gls.testing.client

import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.gvy.gls.services.Health
import com.github.albertocavalcante.gvy.gls.services.StatusNotification
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceFolder
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingDeque

class HarnessLanguageClient : GroovyLanguageClient {
    private val logger = KotlinLogging.logger {}

    private val journal = NotificationJournal()

    private val diagnosticsStorage = LinkedBlockingDeque<PublishDiagnosticsParams>()
    private val messagesStorage = LinkedBlockingDeque<MessageParams>()

    /**
     * CompletableFuture that completes when the server sends `groovy/status` with status="Ready".
     *
     * E2E tests can await this future to synchronize with server initialization instead of
     * using brittle Thread.sleep() calls.
     *
     * @see <a href="https://github.com/albertocavalcante/groovy-lsp/issues/314">Issue #314</a>
     */
    val readyFuture: CompletableFuture<Unit> = CompletableFuture()

    val diagnostics: List<PublishDiagnosticsParams>
        get() = diagnosticsStorage.toList()

    val messages: List<MessageParams>
        get() = messagesStorage.toList()

    override fun telemetryEvent(obj: Any?) {
        recordNotification("telemetry/event", obj)
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        diagnosticsStorage += diagnostics
        recordNotification("textDocument/publishDiagnostics", diagnostics)
    }

    override fun showMessage(messageParams: MessageParams) {
        logger.info { "Received showMessage: type=${messageParams.type}, message='${messageParams.message}'" }
        messagesStorage += messageParams
        recordNotification("window/showMessage", messageParams)
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        recordNotification("window/showMessageRequest", requestParams)
        val response = requestParams.actions?.firstOrNull()
            ?: MessageActionItem("OK")
        return CompletableFuture.completedFuture(response)
    }

    override fun logMessage(message: MessageParams) {
        messagesStorage += message
        recordNotification("window/logMessage", message)
    }

    // LSP4J's Java interface requires CompletableFuture<Void>, rather than Kotlin Unit.
    @Suppress("ForbiddenVoid")
    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> {
        recordNotification("window/workDoneProgress/create", params)
        return CompletableFuture.completedFuture(null)
    }

    override fun notifyProgress(params: ProgressParams) {
        recordNotification("\$/progress", params)
    }

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> {
        recordNotification("workspace/workspaceFolders", null)
        return CompletableFuture.completedFuture(emptyList())
    }

    override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> {
        recordNotification("workspace/configuration", params)
        return CompletableFuture.completedFuture(emptyList())
    }

    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
        recordNotification("workspace/applyEdit", params)
        return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(true))
    }

    // LSP4J's Java interface requires CompletableFuture<Void>, rather than Kotlin Unit.
    @Suppress("ForbiddenVoid")
    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
        recordNotification("client/registerCapability", params)
        return CompletableFuture.completedFuture(null)
    }

    // LSP4J's Java interface requires CompletableFuture<Void>, rather than Kotlin Unit.
    @Suppress("ForbiddenVoid")
    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
        recordNotification("client/unregisterCapability", params)
        return CompletableFuture.completedFuture(null)
    }

    // ============================================================================
    // GROOVY-SPECIFIC NOTIFICATIONS
    // ============================================================================

    /**
     * Handles the `groovy/status` notification from the server.
     *
     * When the server signals "Ready" status, this completes the [readyFuture]
     * allowing E2E tests to proceed with their assertions. If the server signals
     * "Error" status, the future is completed exceptionally so tests fail fast.
     */
    override fun groovyStatus(status: StatusNotification) {
        logger.info {
            "Received groovy/status: health=${status.health}, quiescent=${status.quiescent}, message=${status.message}"
        }
        recordNotification("groovy/status", status)

        when {
            status.health == Health.Ok && status.quiescent -> {
                readyFuture.complete(Unit)
                logger.info { "Server ready - E2E tests can proceed" }
            }

            status.health == Health.Error -> {
                val errorMessage = status.message ?: "Server initialization failed"
                readyFuture.completeExceptionally(RuntimeException(errorMessage))
                logger.error { "Server error - E2E tests will fail: $errorMessage" }
            }

            !status.quiescent -> {
                logger.info {
                    "Server busy (health=${status.health}, message=${status.message}) - waiting for quiescent"
                }
            }

            else -> {
                logger.info {
                    "Server status: health=${status.health}, quiescent=${status.quiescent}, message=${status.message}"
                }
            }
        }
    }

    /** Capture immediately before the action whose notifications a later wait should observe. */
    fun notificationCheckpoint(): Long = journal.checkpoint()

    /** Checks queued, unconsumed notifications without running the predicate under the journal lock. */
    fun peekNotification(method: String, predicate: (Any?) -> Boolean): Boolean = journal.peek(method, predicate)

    /** Each candidate is evaluated once; new arrivals and predicate failures remain available in timeout evidence. */
    fun awaitNotificationDetailed(
        method: String,
        timeoutMs: Long,
        predicate: (Any?) -> Boolean,
        earlySkipPredicate: (() -> Boolean)? = null,
        afterSequence: Long = 0,
    ): WaitResult = journal.await(method, timeoutMs, predicate, earlySkipPredicate, afterSequence)

    private fun recordNotification(method: String, payload: Any?) {
        journal.record(method, payload)
        logger.debug { "Recorded notification $method $payload" }
    }
}

data class NotificationEnvelope(val id: Long, val method: String, val payload: Any?, val timestamp: Instant)

/**
 * Snapshot of a notification for lightweight tracking during wait operations.
 */
data class NotificationSnapshot(val method: String, val timestamp: Instant, val payload: Any?)

/**
 * Information about a notification that matched the method but failed predicate checks.
 */
data class PredicateFailure(val envelope: NotificationEnvelope, val reason: String?)

/**
 * Result of awaiting a notification, including diagnostic information.
 */
data class WaitResult(
    val envelope: NotificationEnvelope?,
    val receivedDuringWait: List<NotificationSnapshot>,
    val matchedMethodButFailed: List<PredicateFailure>,
    val elapsedMs: Long,
    val skipped: Boolean = false,
)
