package com.github.albertocavalcante.gvy.jupyter.core.kernel

import com.github.albertocavalcante.gvy.jupyter.core.handlers.HeartbeatHandler
import com.github.albertocavalcante.gvy.jupyter.core.handlers.MessageHandler
import com.github.albertocavalcante.gvy.jupyter.core.protocol.ConnectionFile
import com.github.albertocavalcante.gvy.jupyter.core.protocol.Header
import com.github.albertocavalcante.gvy.jupyter.core.protocol.JupyterMessage
import com.github.albertocavalcante.gvy.jupyter.core.protocol.MessageType
import com.github.albertocavalcante.gvy.jupyter.core.security.HmacSigner
import com.github.albertocavalcante.gvy.jupyter.core.zmq.JupyterConnection
import com.github.albertocavalcante.gvy.jupyter.core.zmq.WireMessage
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KernelServerSignatureTest {
    private class RecordingHandler : MessageHandler {
        val handled = mutableListOf<JupyterMessage>()
        override fun canHandle(msgType: MessageType) = msgType == MessageType.KERNEL_INFO_REQUEST
        override fun handle(request: JupyterMessage, connection: JupyterConnection) {
            handled.add(request)
        }
    }

    private val connections = mutableListOf<JupyterConnection>()

    @AfterEach
    fun tearDown() = connections.forEach { it.close() }

    private fun server(key: String, handler: MessageHandler): KernelServer {
        val config = ConnectionFile(
            ip = "127.0.0.1",
            transport = "tcp",
            shellPort = 0,
            iopubPort = 0,
            controlPort = 0,
            stdinPort = 0,
            heartbeatPort = 0,
            signatureScheme = "hmac-sha256",
            key = key,
        )
        val connection = JupyterConnection(config, HmacSigner(key)).also { connections.add(it) }
        return KernelServer(connection, listOf(handler), mockk<HeartbeatHandler>())
    }

    private fun frames(signature: String?, signerKey: String = "secret", content: String = "{}"): List<ByteArray> {
        val header = Header(msgType = "kernel_info_request", session = "s").toJson()
        val parts = listOf(header, "{}", "{}", content)
        val sig = signature ?: HmacSigner(signerKey).sign(parts)
        return WireMessage(
            identities = listOf("id".toByteArray()),
            signature = sig,
            header = header,
            parentHeader = "{}",
            metadata = "{}",
            content = content,
        ).toFrames()
    }

    @Test
    fun `valid signature is dispatched`() {
        val handler = RecordingHandler()
        server("secret", handler).dispatchFrames(frames(null), "shell")
        assertEquals(1, handler.handled.size)
    }

    @Test
    fun `wrong signature is dropped without running a handler`() {
        val handler = RecordingHandler()
        server("secret", handler).dispatchFrames(frames(null, signerKey = "attacker"), "shell")
        assertEquals(0, handler.handled.size)
    }

    @Test
    fun `missing signature is dropped when a key is configured`() {
        val handler = RecordingHandler()
        server("secret", handler).dispatchFrames(frames(""), "control")
        assertEquals(0, handler.handled.size)
    }

    @Test
    fun `tampered content invalidates the signature`() {
        val handler = RecordingHandler()
        val good = frames(null)
        val tampered = good.toMutableList().also { it[it.size - 1] = """{"x":1}""".toByteArray() }
        server("secret", handler).dispatchFrames(tampered, "shell")
        assertEquals(0, handler.handled.size)
    }

    @Test
    fun `empty key accepts unsigned messages`() {
        val handler = RecordingHandler()
        server("", handler).dispatchFrames(frames(""), "shell")
        assertEquals(1, handler.handled.size)
    }

    @Test
    fun `signature is verified over the raw received bytes`() {
        // 0xFF is not valid UTF-8: decode/re-encode would change it to U+FFFD and break the signature.
        val header = Header(msgType = "kernel_info_request", session = "s").toJson().toByteArray()
        val content = "{\"x\":\"".toByteArray() + byteArrayOf(0xFF.toByte()) + "\"}".toByteArray()
        val rawParts = listOf(header, "{}".toByteArray(), "{}".toByteArray(), content)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("secret".toByteArray(), "HmacSHA256"))
        rawParts.forEach { mac.update(it) }
        val signature = mac.doFinal().joinToString("") { "%02x".format(it) }
        val frames = listOf("id".toByteArray(), WireMessage.DELIMITER.toByteArray(), signature.toByteArray()) + rawParts

        val handler = RecordingHandler()
        server("secret", handler).dispatchFrames(frames, "shell")

        assertEquals(1, handler.handled.size)
    }
}
