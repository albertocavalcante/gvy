package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import com.github.albertocavalcante.groovyjupyter.zmq.WireMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.zeromq.ZMQ

/**
 * Publishes kernel execution status on the IOPub socket.
 *
 * its execution state. This helps UI show activity indicators.
 */
class StatusPublisher(private val iopubSocket: ZMQ.Socket, private val signer: HmacSigner) {
    private val logger = LoggerFactory.getLogger(StatusPublisher::class.java)

    /**
     * Publish that the kernel is busy processing a request.
     */
    fun publishBusy(parent: JupyterMessage) {
        publishStatus(KernelStatus.BUSY, parent)
    }

    /**
     * Publish that the kernel is idle and ready for new requests.
     */
    fun publishIdle(parent: JupyterMessage) {
        publishStatus(KernelStatus.IDLE, parent)
    }

    private fun publishStatus(status: KernelStatus, parent: JupyterMessage) {
        logger.debug("Publishing status: {}", status.value)

        val header = Header(
            session = parent.header.session,
            username = parent.header.username,
            msgType = MessageType.STATUS.value,
        )

        // Use proper JSON serialization instead of manual string construction
        val content = buildJsonObject {
            put("execution_state", status.value)
        }.toString()

        val wireMessage = WireMessage(
            identities = emptyList(),
            signature = "", // Will be computed
            header = header.toJson(),
            parentHeader = parent.header.toJson(),
            metadata = "{}",
            content = content,
        )

        val frames = wireMessage.toSignedFrames(signer)
        sendMultipart(frames)

        logger.trace("Status {} published", status.value)
    }

    private fun sendMultipart(frames: List<ByteArray>) {
        frames.forEachIndexed { index, frame ->
            val sendMore = index < frames.size - 1
            iopubSocket.send(frame, if (sendMore) ZMQ.SNDMORE else 0)
        }
    }
}
