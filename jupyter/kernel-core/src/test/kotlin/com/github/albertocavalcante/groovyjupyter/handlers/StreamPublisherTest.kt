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
 * TDD tests for StreamPublisher - publishes stdout/stderr on IOPub.
 *
 * The kernel publishes stream output during code execution so
 * frontends can display it in real-time.
 */
class StreamPublisherTest {

    private lateinit var context: ZContext
    private lateinit var pubSocket: ZMQ.Socket
    private lateinit var subSocket: ZMQ.Socket
    private val signer = HmacSigner("test-key")
    private val endpoint = "inproc://stream-test"

    @BeforeEach
    fun setup() {
        context = ZContext()

        pubSocket = context.createSocket(SocketType.PUB)
        pubSocket.bind(endpoint)

        subSocket = context.createSocket(SocketType.SUB)
        subSocket.connect(endpoint)
        subSocket.subscribe("".toByteArray())

        // Use socket timeout for reliable tests instead of Thread.sleep
        subSocket.receiveTimeOut = RECEIVE_TIMEOUT_MS

        // Small delay for inproc connection setup
        Thread.sleep(10)
    }

    @AfterEach
    fun cleanup() {
        subSocket.close()
        pubSocket.close()
        context.close()
    }

    @Test
    fun `should publish stdout stream`() {
        // Given: A stream publisher
        val publisher = StreamPublisher(pubSocket, signer)
        val parent = createTestMessage()

        // When: Publishing stdout
        publisher.publishStdout("Hello from stdout", parent)

        // Then: Should send stream message
        val frames = receiveMultipart()
        assertThat(frames).isNotEmpty

        val wireMsg = WireMessage.fromFrames(frames)
        assertThat(wireMsg.content).contains("\"name\":\"stdout\"")
        assertThat(wireMsg.content).contains("Hello from stdout")
    }

    @Test
    fun `should publish stderr stream`() {
        // Given: A stream publisher
        val publisher = StreamPublisher(pubSocket, signer)
        val parent = createTestMessage()

        // When: Publishing stderr
        publisher.publishStderr("Error output", parent)

        // Then: Should send stream message
        val frames = receiveMultipart()
        assertThat(frames).isNotEmpty

        val wireMsg = WireMessage.fromFrames(frames)
        assertThat(wireMsg.content).contains("\"name\":\"stderr\"")
        assertThat(wireMsg.content).contains("Error output")
    }

    @Test
    fun `should include parent header in stream messages`() {
        // Given: A publisher and parent with specific session
        val publisher = StreamPublisher(pubSocket, signer)
        val parent = createTestMessage(session = "stream-session")

        // When: Publishing stream
        publisher.publishStdout("test", parent)

        // Then: Parent header should reference original message
        val frames = receiveMultipart()
        val wireMsg = WireMessage.fromFrames(frames)
        assertThat(wireMsg.parentHeader).contains("stream-session")
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
        // Use blocking receive with timeout (set in setup)
        val firstFrame = subSocket.recv() ?: return frames
        frames.add(firstFrame)
        while (subSocket.hasReceiveMore()) {
            val frame = subSocket.recv() ?: break
            frames.add(frame)
        }
        return frames
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 1000
    }
}
