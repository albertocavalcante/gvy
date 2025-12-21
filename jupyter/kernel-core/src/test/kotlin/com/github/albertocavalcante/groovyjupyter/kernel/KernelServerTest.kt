package com.github.albertocavalcante.groovyjupyter.kernel

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteHandler
import com.github.albertocavalcante.groovyjupyter.handlers.KernelInfoHandler
import com.github.albertocavalcante.groovyjupyter.kernel.core.KernelExecutor
import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernelServerTest {

    @Test
    fun `should create server with handlers`() {
        // Given: A server configuration
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val executor = mockk<KernelExecutor>()

        // When: Creating server
        val server = KernelServer.create(config, signer, executor)

        // Then: Should have handlers registered
        assertEquals(3, server.handlers.size) // KernelInfo, Execute, Shutdown
    }

    @Test
    fun `should shutdown gracefully`() {
        // Given: A server
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, mockk<KernelExecutor>())

        // When: Requesting shutdown
        server.shutdown()

        // Then: Server should be marked as not running
        assertFalse(server.isRunning)
    }

    @Test
    fun `should find handler for message type`() {
        // Given: A server with handlers
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, mockk<KernelExecutor>())

        // When: Finding handler for kernel_info_request
        val handler = server.findHandler(MessageType.KERNEL_INFO_REQUEST)

        // Then: Should find KernelInfoHandler
        assertNotNull(handler)
        assertTrue(handler is KernelInfoHandler)
    }

    @Test
    fun `should find handler for execute_request`() {
        // Given: A server with handlers
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, mockk<KernelExecutor>())

        // When: Finding handler for execute_request
        val handler = server.findHandler(MessageType.EXECUTE_REQUEST)

        // Then: Should find ExecuteHandler
        assertNotNull(handler)
        assertTrue(handler is ExecuteHandler)
    }

    @Test
    fun `should return null for unknown message type`() {
        // Given: A server with handlers
        val config = createTestConfig()
        val signer = HmacSigner("test-key")
        val server = KernelServer.create(config, signer, mockk<KernelExecutor>())

        // When: Finding handler for unknown type
        val handler = server.findHandler(MessageType.COMPLETE_REQUEST)

        // Then: Should return null
        assertNull(handler)
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
