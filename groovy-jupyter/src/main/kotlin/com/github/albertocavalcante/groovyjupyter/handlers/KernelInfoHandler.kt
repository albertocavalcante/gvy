package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import groovy.lang.GroovySystem
import org.slf4j.LoggerFactory

/**
 * Handles kernel_info_request messages.
 *
 * When Jupyter connects, it sends kernel_info_request to discover
 * the kernel's capabilities, language, and protocol version.
 */
class KernelInfoHandler(
    private val statusPublisherFactory: (JupyterConnection) -> StatusPublisher = { conn ->
        StatusPublisher(conn.iopubSocket, conn.signer)
    },
) : MessageHandler {
    private val logger = LoggerFactory.getLogger(KernelInfoHandler::class.java)

    override fun canHandle(msgType: MessageType): Boolean = msgType == MessageType.KERNEL_INFO_REQUEST

    override fun handle(request: JupyterMessage, connection: JupyterConnection) {
        logger.info("Handling kernel_info_request")

        val statusPublisher = statusPublisherFactory(connection)

        // 1. Publish busy status
        statusPublisher.publishBusy(request)

        // 2. Create and send kernel_info_reply
        val reply = createReply(request)
        // TODO: Send reply on shell socket (will be implemented with message sending)

        // 3. Publish idle status
        statusPublisher.publishIdle(request)

        logger.info("Completed kernel_info_request")
    }

    /**
     * Build the kernel info content map.
     */
    fun buildKernelInfo(): Map<String, Any> = mapOf(
        "protocol_version" to PROTOCOL_VERSION,
        "implementation" to IMPLEMENTATION,
        "implementation_version" to IMPLEMENTATION_VERSION,
        "language_info" to buildLanguageInfo(),
        "banner" to "Groovy Jupyter Kernel v$IMPLEMENTATION_VERSION\nPowered by ${GroovySystem.getVersion()}",
        "help_links" to emptyList<Map<String, String>>(),
        "status" to "ok",
    )

    /**
     * Create a kernel_info_reply message from a request.
     */
    fun createReply(request: JupyterMessage): JupyterMessage {
        val reply = request.createReply(MessageType.KERNEL_INFO_REPLY)
        return reply.copy(content = buildKernelInfo())
    }

    private fun buildLanguageInfo(): Map<String, Any> = mapOf(
        "name" to "groovy",
        "version" to GroovySystem.getVersion(),
        "mimetype" to "text/x-groovy",
        "file_extension" to ".groovy",
        "pygments_lexer" to "groovy",
        "codemirror_mode" to "groovy",
    )

    private companion object {
        const val PROTOCOL_VERSION = "5.3"
        const val IMPLEMENTATION = "groovy-jupyter"
        const val IMPLEMENTATION_VERSION = "0.1.0"
    }
}
