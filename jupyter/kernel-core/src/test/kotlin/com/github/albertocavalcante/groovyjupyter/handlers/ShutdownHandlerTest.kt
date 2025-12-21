package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TDD tests for ShutdownHandler - handles shutdown_request messages.
 */
class ShutdownHandlerTest {

    @Test
    fun `should handle shutdown_request message type`() {
        // Given: A shutdown handler
        val handler = ShutdownHandler()

        // Then: Should handle shutdown_request
        assertThat(handler.canHandle(MessageType.SHUTDOWN_REQUEST)).isTrue()
    }

    @Test
    fun `should not handle other message types`() {
        // Given: A shutdown handler
        val handler = ShutdownHandler()

        // Then: Should not handle other types
        assertThat(handler.canHandle(MessageType.EXECUTE_REQUEST)).isFalse()
        assertThat(handler.canHandle(MessageType.KERNEL_INFO_REQUEST)).isFalse()
    }

    @Test
    fun `should extract restart flag from request`() {
        // Given: A shutdown request with restart=true
        val request = createShutdownRequest(restart = true)
        val handler = ShutdownHandler()

        // When: Getting restart flag
        val restart = handler.shouldRestart(request)

        // Then: Should be true
        assertThat(restart).isTrue()
    }

    @Test
    fun `should default restart to false`() {
        // Given: A shutdown request without restart flag
        val request = JupyterMessage(
            header = Header(
                msgId = "test",
                session = "test",
                username = "test",
                msgType = MessageType.SHUTDOWN_REQUEST.value,
            ),
            content = emptyMap(),
        )
        val handler = ShutdownHandler()

        // When: Getting restart flag
        val restart = handler.shouldRestart(request)

        // Then: Should default to false
        assertThat(restart).isFalse()
    }

    @Test
    fun `should send shutdown_reply on control socket with restart flag`() {
        // Given: A shutdown request with restart=true
        val request = createShutdownRequest(restart = true)
        val connection = mockk<JupyterConnection>(relaxed = true)
        var shutdownCalled = false
        val handler = ShutdownHandler(onShutdown = { shutdownCalled = true })

        // When: Handling shutdown request
        handler.handle(request, connection)

        // Then: Should send shutdown_reply on control socket
        verify {
            connection.sendMessage(
                match {
                    it.header.msgType == MessageType.SHUTDOWN_REPLY.value &&
                        it.content["restart"] == true
                },
                connection.controlSocket,
            )
        }

        // And shutdown callback should be called
        assertThat(shutdownCalled).isTrue()
    }

    @Test
    fun `should send shutdown_reply with restart false when restart is false`() {
        // Given: A shutdown request with restart=false
        val request = createShutdownRequest(restart = false)
        val connection = mockk<JupyterConnection>(relaxed = true)
        val handler = ShutdownHandler()

        // When: Handling shutdown request
        handler.handle(request, connection)

        // Then: Should send shutdown_reply with restart=false
        verify {
            connection.sendMessage(
                match {
                    it.header.msgType == MessageType.SHUTDOWN_REPLY.value &&
                        it.content["restart"] == false
                },
                connection.controlSocket,
            )
        }
    }

    @Test
    fun `should call shutdown callback`() {
        // Given: A shutdown request
        val request = createShutdownRequest(restart = false)
        val connection = mockk<JupyterConnection>(relaxed = true)
        var callbackInvoked = false
        val handler = ShutdownHandler(onShutdown = { callbackInvoked = true })

        // When: Handling shutdown request
        handler.handle(request, connection)

        // Then: Callback should be invoked
        assertThat(callbackInvoked).isTrue()
    }

    private fun createShutdownRequest(restart: Boolean): JupyterMessage = JupyterMessage(
        header = Header(
            msgId = "test-msg-id",
            session = "test-session",
            username = "test-user",
            msgType = MessageType.SHUTDOWN_REQUEST.value,
        ),
        content = mapOf("restart" to restart),
    )
}
