package com.github.albertocavalcante.groovyjupyter.kernel

import com.github.albertocavalcante.groovyjupyter.execution.GroovyExecutor
import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteHandler
import com.github.albertocavalcante.groovyjupyter.handlers.HeartbeatHandler
import com.github.albertocavalcante.groovyjupyter.handlers.KernelInfoHandler
import com.github.albertocavalcante.groovyjupyter.handlers.MessageHandler
import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import com.github.albertocavalcante.groovyjupyter.zmq.WireMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * TDD tests for KernelServer - the main loop that dispatches messages.
 *
 * The server polls sockets and dispatches to handlers.
 */
class KernelServerTest {

    @Test
    fun `should create server with handlers`() {
        // Given: A server configuration
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val executor = GroovyExecutor()

        // When: Creating server
        val server = KernelServer.create(config, signer, executor)

        // Then: Should have handlers registered
        assertThat(server.handlers).hasSize(3) // KernelInfo, Execute, Shutdown
    }

    @Test
    fun `should shutdown gracefully`() {
        // Given: A server
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, GroovyExecutor())

        // When: Requesting shutdown
        server.shutdown()

        // Then: Server should be marked as not running
        assertThat(server.isRunning).isFalse()
    }

    @Test
    fun `should find handler for message type`() {
        // Given: A server with handlers
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, GroovyExecutor())

        // When: Finding handler for kernel_info_request
        val handler = server.findHandler(MessageType.KERNEL_INFO_REQUEST)

        // Then: Should find KernelInfoHandler
        assertThat(handler).isNotNull
        assertThat(handler).isInstanceOf(KernelInfoHandler::class.java)
    }

    @Test
    fun `should find handler for execute_request`() {
        // Given: A server with handlers
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, GroovyExecutor())

        // When: Finding handler for execute_request
        val handler = server.findHandler(MessageType.EXECUTE_REQUEST)

        // Then: Should find ExecuteHandler
        assertThat(handler).isNotNull
        assertThat(handler).isInstanceOf(ExecuteHandler::class.java)
    }

    @Test
    fun `should return null for unknown message type`() {
        // Given: A server with handlers
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, GroovyExecutor())

        // When: Finding handler for unknown type
        val handler = server.findHandler(MessageType.COMPLETE_REQUEST)

        // Then: Should return null
        assertThat(handler).isNull()
    }

    private fun createTestConfig(): ConnectionFile = ConnectionFile(
        ip = "127.0.0.1",
        transport = "tcp",
        shellPort = 0,
        iopubPort = 0,
        controlPort = 0,
        stdinPort = 0,
        heartbeatPort = 0,
        signatureScheme = "hmac-sha256",
        key = "test-key",
    )
}
