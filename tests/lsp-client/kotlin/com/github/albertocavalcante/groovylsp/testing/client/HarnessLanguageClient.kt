package com.github.albertocavalcante.groovylsp.testing.client

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
import org.eclipse.lsp4j.services.LanguageClient
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HarnessLanguageClient : LanguageClient {
    private val logger = LoggerFactory.getLogger(HarnessLanguageClient::class.java)

    private val notifications = mutableListOf<NotificationEnvelope>()
    private val consumedNotificationIds = mutableSetOf<Long>()
    private val notificationSequence = AtomicLong(0)
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    private val diagnosticsStorage = LinkedBlockingDeque<PublishDiagnosticsParams>()
    private val messagesStorage = LinkedBlockingDeque<MessageParams>()

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
        logger.info("Received showMessage: type={}, message='{}'", messageParams.type, messageParams.message)
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

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
        recordNotification("client/registerCapability", params)
        return CompletableFuture.completedFuture(null)
    }

    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
        recordNotification("client/unregisterCapability", params)
        return CompletableFuture.completedFuture(null)
    }

    fun awaitNotification(method: String, timeoutMs: Long, predicate: (Any?) -> Boolean): NotificationEnvelope? {
        val result = awaitNotificationDetailed(method, timeoutMs, predicate)
        return result.envelope
    }

    /**
     * Peeks if a notification matching the given criteria exists in the queue without consuming it.
     * Used for optional steps to detect if the next step's notification has already arrived.
     *
     * @param method The notification method to look for
     * @param predicate The predicate to match the notification payload
     * @return true if a matching notification exists in the queue, false otherwise
     */
    fun peekNotification(method: String, predicate: (Any?) -> Boolean): Boolean {
        lock.withLock {
            return notifications.any { envelope ->
                envelope.id !in consumedNotificationIds &&
                    envelope.method == method &&
                    try {
                        predicate(envelope.payload)
                    } catch (e: Exception) {
                        false
                    }
            }
        }
    }

    /**
     * Awaits a notification with detailed diagnostic information.
     * Returns a WaitResult containing the envelope (if found), notifications received during wait,
     * notifications that matched the method but failed the predicate, and elapsed time.
     *
     * @param earlySkipPredicate Optional predicate checked periodically. If it returns true,
     *        the wait is skipped early with a null envelope and skipped=true in result.
     */
    fun awaitNotificationDetailed(
        method: String,
        timeoutMs: Long,
        predicate: (Any?) -> Boolean,
        earlySkipPredicate: (() -> Boolean)? = null,
    ): WaitResult {
        val startTime = System.nanoTime()
        val deadline = startTime + timeoutMs * 1_000_000
        val receivedDuringWait = mutableListOf<NotificationSnapshot>()
        val matchedButFailed = mutableListOf<PredicateFailure>()

        // Enable progress logging for waits > 10 seconds
        val enableProgressLogging = timeoutMs > 10_000
        val progressIntervalNs = 5_000_000_000L // 5 seconds in nanoseconds
        var nextProgressLogTime = if (enableProgressLogging) startTime + progressIntervalNs else Long.MAX_VALUE

        if (enableProgressLogging) {
            logger.info("Waiting for notification '{}' (timeout: {}ms)", method, timeoutMs)
        }

        lock.withLock {
            // Snapshot current notification count to track new arrivals
            val initialNotificationCount = notifications.size

            // Check for pre-existing notifications that match method but fail predicate
            // These were sent before we started waiting but are still relevant for diagnostics
            val preExistingMatches = notifications.filter { envelope ->
                envelope.id !in consumedNotificationIds &&
                    envelope.method == method &&
                    !predicate(envelope.payload)
            }

            preExistingMatches.forEach { envelope ->
                // Add to matchedButFailed for diagnostics
                matchedButFailed.add(PredicateFailure(envelope, "Predicate returned false"))

                // Also add to receivedDuringWait so they appear in the exception message
                receivedDuringWait.add(
                    NotificationSnapshot(
                        method = envelope.method,
                        timestamp = envelope.timestamp,
                        payload = envelope.payload,
                    ),
                )
            }

            while (true) {
                // Track all new notifications that arrived during this wait
                if (notifications.size > receivedDuringWait.size + initialNotificationCount) {
                    val newNotifications = notifications.drop(initialNotificationCount + receivedDuringWait.size)
                    newNotifications.forEach { envelope ->
                        receivedDuringWait.add(
                            NotificationSnapshot(
                                method = envelope.method,
                                timestamp = envelope.timestamp,
                                payload = envelope.payload,
                            ),
                        )
                    }
                }

                // Check if we should skip early (e.g., next step's notification arrived)
                if (earlySkipPredicate?.invoke() == true) {
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    logger.info("Early skip triggered after {}ms - next notification ready", elapsedMs)
                    return WaitResult(
                        envelope = null,
                        receivedDuringWait = receivedDuringWait,
                        matchedMethodButFailed = matchedButFailed,
                        elapsedMs = elapsedMs,
                        skipped = true,
                    )
                }

                // Try to find a matching notification
                val match = notifications.firstOrNull { envelope ->
                    envelope.id !in consumedNotificationIds && envelope.method == method && predicate(envelope.payload)
                }

                if (match != null) {
                    consumedNotificationIds += match.id
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    if (enableProgressLogging) {
                        logger.info("Notification '{}' received after {}ms", method, elapsedMs)
                    }
                    return WaitResult(
                        envelope = match,
                        receivedDuringWait = receivedDuringWait,
                        matchedMethodButFailed = matchedButFailed,
                        elapsedMs = elapsedMs,
                    )
                }

                // Check for notifications that matched method but failed predicate
                notifications.forEach { envelope ->
                    if (envelope.id !in consumedNotificationIds &&
                        envelope.method == method &&
                        !predicate(envelope.payload) &&
                        matchedButFailed.none { it.envelope.id == envelope.id }
                    ) {
                        // Try to get failure reason by calling predicate in a way that captures the error
                        val reason = try {
                            predicate(envelope.payload)
                            null // Shouldn't happen, but just in case
                        } catch (e: Exception) {
                            e.message
                        } ?: "Predicate returned false"

                        matchedButFailed.add(PredicateFailure(envelope, reason))
                    }
                }

                val now = System.nanoTime()
                val remaining = deadline - now

                // Check if we should log progress
                if (enableProgressLogging && now >= nextProgressLogTime) {
                    val elapsedSec = (now - startTime) / 1_000_000_000
                    val matchedCount = matchedButFailed.size
                    logger.info(
                        "Still waiting... {}s elapsed, received {} notifications ({} matched method but failed check)",
                        elapsedSec,
                        receivedDuringWait.size,
                        matchedCount,
                    )
                    nextProgressLogTime = now + progressIntervalNs
                }

                if (remaining <= 0) {
                    val elapsedMs = (now - startTime) / 1_000_000
                    return WaitResult(
                        envelope = null,
                        receivedDuringWait = receivedDuringWait,
                        matchedMethodButFailed = matchedButFailed,
                        elapsedMs = elapsedMs,
                        skipped = false,
                    )
                }

                // Wait for next notification or progress interval, whichever comes first
                val waitTime = if (enableProgressLogging) {
                    minOf(remaining, nextProgressLogTime - now)
                } else {
                    remaining
                }
                condition.awaitNanos(waitTime)
            }
        }
    }

    private fun recordNotification(method: String, payload: Any?) {
        val envelope = NotificationEnvelope(
            id = notificationSequence.incrementAndGet(),
            method = method,
            payload = payload,
            timestamp = Instant.now(),
        )

        lock.withLock {
            notifications.add(envelope)
            condition.signalAll()
        }

        logger.debug("Recorded notification {} {}", method, payload)
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
