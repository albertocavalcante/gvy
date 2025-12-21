package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import org.slf4j.LoggerFactory

class KernelInfoHandler(
    private val languageName: String = "groovy",
    private val languageVersion: String = "4.0.0", // Default, should be injected
    private val implementationVersion: String = "0.1.0",
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
        // Send reply directly
        connection.sendMessage(reply)

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
        "implementation_version" to implementationVersion,
        "language_info" to buildLanguageInfo(),
        "banner" to "Groovy Jupyter Kernel v$implementationVersion\nPowered by $languageName $languageVersion",
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
        "name" to languageName,
        "version" to languageVersion,
        "mimetype" to "text/x-$languageName",
        "file_extension" to ".$languageName",
        "pygments_lexer" to languageName,
        "codemirror_mode" to languageName,
    )

    private companion object {
        const val PROTOCOL_VERSION = "5.3"
        const val IMPLEMENTATION = "groovy-jupyter"
    }
}
