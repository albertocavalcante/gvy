package com.github.albertocavalcante.groovyjupyter.handlers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import kotlin.text.Charsets

/**
 * TDD tests for HeartbeatHandler - simple echo for kernel liveness checks.
 *
 * The heartbeat socket is a REP socket that receives ping messages
 * and echoes them back unchanged. Jupyter uses this to detect if
 * the kernel is still alive.
 */
class HeartbeatHandlerTest {

    private lateinit var context: ZContext
    private lateinit var serverSocket: ZMQ.Socket
    private lateinit var clientSocket: ZMQ.Socket
    private val endpoint = "inproc://hb-test"

    @BeforeEach
    fun setup() {
        context = ZContext()

        // Server side: REP socket (what the kernel uses)
        serverSocket = context.createSocket(SocketType.REP)
        serverSocket.bind(endpoint)

        // Client side: REQ socket (simulates Jupyter frontend)
        clientSocket = context.createSocket(SocketType.REQ)
        clientSocket.connect(endpoint)
    }

    @AfterEach
    fun cleanup() {
        clientSocket.close()
        serverSocket.close()
        context.close()
    }

    @Test
    fun `should echo received message`() {
        // Given: A heartbeat handler and a pending message
        val handler = HeartbeatHandler(serverSocket)
        val pingMessage = "ping".toByteArray(Charsets.UTF_8)

        // Send message from client
        clientSocket.send(pingMessage)

        // When: Handling the heartbeat
        val handled = handler.handleOnce()

        // Then: Should have handled a message
        assertThat(handled).isTrue()

        // And: Client should receive the echoed message
        val response = clientSocket.recv()
        assertThat(response).isEqualTo(pingMessage)
    }

    @Test
    fun `should echo binary data unchanged`() {
        // Given: Binary data
        val handler = HeartbeatHandler(serverSocket)
        val binaryData = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())

        clientSocket.send(binaryData)

        // When: Handling
        handler.handleOnce()

        // Then: Binary data should be echoed exactly
        val response = clientSocket.recv()
        assertThat(response).isEqualTo(binaryData)
    }

    @Test
    fun `should return false when no message available`() {
        // Given: A handler with no pending messages
        val handler = HeartbeatHandler(serverSocket)

        // When: Trying to handle with no messages (non-blocking)
        val handled = handler.handleOnce()

        // Then: Should return false
        assertThat(handled).isFalse()
    }

    @Test
    fun `should handle multiple heartbeats in sequence`() {
        // Given: A handler
        val handler = HeartbeatHandler(serverSocket)

        // When: Sending multiple heartbeats
        repeat(3) { i ->
            val msg = "heartbeat-$i".toByteArray(Charsets.UTF_8)
            clientSocket.send(msg)
            handler.handleOnce()
            val response = clientSocket.recv()
            assertThat(String(response, Charsets.UTF_8)).isEqualTo("heartbeat-$i")
        }
    }
}
