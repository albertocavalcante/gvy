package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import com.github.albertocavalcante.groovyjupyter.zmq.WireMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.zeromq.ZMQ

/**
 * Represents stream output types.
 */
enum class StreamName(val value: String) {
    STDOUT("stdout"),
    STDERR("stderr"),
}

/**
 * Publishes stream output (stdout/stderr) on the IOPub socket.
 *
 * The kernel publishes stream messages during code execution so
 * frontends can display output in real-time.
 */
class StreamPublisher(private val iopubSocket: ZMQ.Socket, private val signer: HmacSigner) {
    private val logger = KotlinLogging.logger {}

    /**
     * Publish stdout output.
     */
    fun publishStdout(text: String, parent: JupyterMessage) {
        publishStream(StreamName.STDOUT, text, parent)
    }

    /**
     * Publish stderr output.
     */
    fun publishStderr(text: String, parent: JupyterMessage) {
        publishStream(StreamName.STDERR, text, parent)
    }

    private fun publishStream(name: StreamName, text: String, parent: JupyterMessage) {
        if (text.isEmpty()) return

        logger.debug { "Publishing stream ${name.value}: ${text.length} chars" }

        val header = Header(
            session = parent.header.session,
            username = parent.header.username,
            msgType = MessageType.STREAM.value,
        )

        val content = buildJsonObject {
            put("name", name.value)
            put("text", text)
        }.toString()

        val wireMessage = WireMessage(
            identities = emptyList(),
            signature = "",
            header = header.toJson(),
            parentHeader = parent.header.toJson(),
            metadata = "{}",
            content = content,
        )

        val frames = wireMessage.toSignedFrames(signer)
        sendMultipart(frames)

        logger.trace { "Stream ${name.value} published" }
    }

    private fun sendMultipart(frames: List<ByteArray>) {
        frames.forEachIndexed { index, frame ->
            val sendMore = index < frames.size - 1
            iopubSocket.send(frame, if (sendMore) ZMQ.SNDMORE else 0)
        }
    }
}
