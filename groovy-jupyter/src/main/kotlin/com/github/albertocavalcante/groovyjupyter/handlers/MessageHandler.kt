package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection

/**
 * Interface for message handlers in the Jupyter kernel.
 *
 * Each handler is responsible for processing a specific type of message
 * (e.g., kernel_info_request, execute_request) and sending appropriate
 * responses.
 */
interface MessageHandler {
    /**
     * Check if this handler can process the given message type.
     */
    fun canHandle(msgType: MessageType): Boolean

    /**
     * Handle the incoming message and send responses.
     *
     * @param request The incoming Jupyter message
     * @param connection The connection to use for sending responses
     */
    fun handle(request: JupyterMessage, connection: JupyterConnection)
}
