package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import org.slf4j.LoggerFactory

/**
 * Handles shutdown_request messages.
 *
 * When Jupyter sends shutdown_request, the kernel should clean up and exit.
 * If restart=true, Jupyter will restart the kernel after shutdown.
 */
class ShutdownHandler(private val onShutdown: () -> Unit = {}) : MessageHandler {
    private val logger = LoggerFactory.getLogger(ShutdownHandler::class.java)

    override fun canHandle(msgType: MessageType): Boolean = msgType == MessageType.SHUTDOWN_REQUEST

    override fun handle(request: JupyterMessage, connection: JupyterConnection) {
        val restart = shouldRestart(request)
        logger.info("Handling shutdown_request (restart={})", restart)

        // TODO: Send shutdown_reply on control socket

        // Trigger shutdown callback
        onShutdown()

        logger.info("Shutdown initiated")
    }

    /**
     * Check if the kernel should restart after shutdown.
     */
    fun shouldRestart(request: JupyterMessage): Boolean = request.content["restart"] as? Boolean ?: false
}
