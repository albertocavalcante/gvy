package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import com.github.albertocavalcante.groovyjupyter.zmq.WireMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ

/**
 * TDD tests for StatusPublisher - publishes kernel status on IOPub.
 *
 * The kernel publishes busy/idle status to inform frontends
 * about its execution state.
 */
class StatusPublisherTest {

    private lateinit var context: ZContext
    private lateinit var pubSocket: ZMQ.Socket
    private lateinit var subSocket: ZMQ.Socket
    private val signer = HmacSigner("test-key")
    private val endpoint = "inproc://status-test"

    @BeforeEach
    fun setup() {
        context = ZContext()

        // Publisher: what the kernel uses
        pubSocket = context.createSocket(SocketType.PUB)
        pubSocket.bind(endpoint)

        // Subscriber: simulates frontend
        subSocket = context.createSocket(SocketType.SUB)
        subSocket.connect(endpoint)
        subSocket.subscribe("".toByteArray()) // Subscribe to all

        // TODO: Replace with synchronization mechanism for more reliable tests.
        // Thread.sleep can be flaky on slow machines. Consider using a ready signal
        // or polling with timeout for production-grade test reliability.
        Thread.sleep(50)
    }

    @AfterEach
    fun cleanup() {
        subSocket.close()
        pubSocket.close()
        context.close()
    }

    @Test
    fun `should publish busy status`() {
        // Given: A status publisher and parent message
        val publisher = StatusPublisher(pubSocket, signer)
        val parent = createTestMessage()

        // When: Publishing busy status
        publisher.publishBusy(parent)

        // Then: Should send status message
        val frames = receiveMultipart()
        assertThat(frames).isNotEmpty

        val wireMsg = WireMessage.fromFrames(frames)
        assertThat(wireMsg.content).contains("\"execution_state\":\"busy\"")
    }

    @Test
    fun `should publish idle status`() {
        // Given: A status publisher and parent message
        val publisher = StatusPublisher(pubSocket, signer)
        val parent = createTestMessage()

        // When: Publishing idle status
        publisher.publishIdle(parent)

        // Then: Should send status message
        val frames = receiveMultipart()
        assertThat(frames).isNotEmpty

        val wireMsg = WireMessage.fromFrames(frames)
        assertThat(wireMsg.content).contains("\"execution_state\":\"idle\"")
    }

    @Test
    fun `should include parent header in status messages`() {
        // Given: A publisher and parent with specific session
        val publisher = StatusPublisher(pubSocket, signer)
        val parent = createTestMessage(session = "my-session-123")

        // When: Publishing status
        publisher.publishBusy(parent)

        // Then: Parent header should reference original message
        val frames = receiveMultipart()
        val wireMsg = WireMessage.fromFrames(frames)
        assertThat(wireMsg.parentHeader).contains("my-session-123")
    }

    private fun createTestMessage(session: String = "test-session"): JupyterMessage = JupyterMessage(
        header = Header(
            msgId = "parent-id",
            session = session,
            username = "test-user",
            msgType = MessageType.EXECUTE_REQUEST.value,
        ),
    )

    private fun receiveMultipart(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var more = true
        while (more) {
            val frame = subSocket.recv(ZMQ.DONTWAIT) ?: break
            frames.add(frame)
            more = subSocket.hasReceiveMore()
        }
        return frames
    }
}
