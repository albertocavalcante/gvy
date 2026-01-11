package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Handles shutdown_request messages.
 *
 * When Jupyter sends shutdown_request, the kernel should clean up and exit.
 * If restart=true, Jupyter will restart the kernel after shutdown.
 */
class ShutdownHandler(private val onShutdown: () -> Unit = {}) : MessageHandler {
    private val logger = KotlinLogging.logger {}

    override fun canHandle(msgType: MessageType): Boolean = msgType == MessageType.SHUTDOWN_REQUEST

    override fun handle(request: JupyterMessage, connection: JupyterConnection) {
        val restart = shouldRestart(request)
        logger.info { "Handling shutdown_request (restart=$restart)" }

        // Send shutdown_reply on control socket
        val reply = request.createReply(MessageType.SHUTDOWN_REPLY).apply {
            content = mapOf("restart" to restart)
        }
        connection.sendMessage(reply, connection.controlSocket)

        // Trigger shutdown callback
        onShutdown()

        logger.info { "Shutdown initiated" }
    }

    /**
     * Check if the kernel should restart after shutdown.
     */
    fun shouldRestart(request: JupyterMessage): Boolean = request.content["restart"] as? Boolean ?: false
}
