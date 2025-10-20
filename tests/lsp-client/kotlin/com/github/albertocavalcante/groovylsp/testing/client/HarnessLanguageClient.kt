package com.github.albertocavalcante.groovylsp.testing.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
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

class HarnessLanguageClient(private val mapper: ObjectMapper) : LanguageClient {
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

    fun awaitNotification(method: String, timeoutMs: Long, predicate: (JsonNode?) -> Boolean): NotificationEnvelope? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        lock.withLock {
            while (true) {
                val match = notifications.firstOrNull { envelope ->
                    envelope.method == method &&
                        envelope.id !in consumedNotificationIds &&
                        predicate(envelope.payload)
                }
                if (match != null) {
                    consumedNotificationIds += match.id
                    return match
                }

                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    return null
                }
                condition.awaitNanos(remaining)
            }
        }
    }

    private fun recordNotification(method: String, payload: Any?) {
        val payloadNode = when (payload) {
            null -> NullNode.instance
            is JsonNode -> payload
            else -> mapper.valueToTree(payload) ?: NullNode.instance
        }

        val envelope = NotificationEnvelope(
            id = notificationSequence.incrementAndGet(),
            method = method,
            payload = payloadNode,
            timestamp = Instant.now(),
        )

        lock.withLock {
            notifications.add(envelope)
            condition.signalAll()
        }

        logger.debug("Recorded notification {} {}", method, payloadNode)
    }
}

data class NotificationEnvelope(val id: Long, val method: String, val payload: JsonNode?, val timestamp: Instant)
