package com.github.albertocavalcante.groovyjupyter.zmq

import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * TDD tests for JupyterConnection - ZMQ socket lifecycle management.
 *
 * Note: These tests use real ZMQ sockets with random available ports.
 * We test socket creation, binding, and cleanup behavior.
 */
class JupyterConnectionTest {

    private var connection: JupyterConnection? = null

    @AfterEach
    fun cleanup() {
        connection?.close()
    }

    @Test
    fun `should create all five sockets`() {
        // Given: A connection configuration
        val config = createTestConnectionFile()
        val signer = HmacSigner("test-key")

        // When: Creating connection
        connection = JupyterConnection(config, signer)

        // Then: All sockets should be accessible
        assertThat(connection?.shellSocket).isNotNull
        assertThat(connection?.iopubSocket).isNotNull
        assertThat(connection?.controlSocket).isNotNull
        assertThat(connection?.stdinSocket).isNotNull
        assertThat(connection?.heartbeatSocket).isNotNull
    }

    @Test
    fun `should bind sockets on start`() {
        // Given: A connection
        val config = createTestConnectionFile()
        val signer = HmacSigner("test-key")
        connection = JupyterConnection(config, signer)

        // When: Starting the connection
        connection?.bind()

        // Then: Should not throw (sockets bound successfully)
        // Note: We can't easily verify binding without connecting,
        // but lack of exception indicates success
        assertThat(connection?.isBound).isTrue()
    }

    @Test
    fun `should close sockets cleanly`() {
        // Given: A bound connection
        val config = createTestConnectionFile()
        val signer = HmacSigner("test-key")
        connection = JupyterConnection(config, signer)
        connection?.bind()

        // When: Closing
        connection?.close()

        // Then: Should be closed
        assertThat(connection?.isClosed).isTrue()
    }

    @Test
    fun `should provide signer for message authentication`() {
        // Given: A connection with a specific key
        val config = createTestConnectionFile()
        val signer = HmacSigner("my-secret-key")
        connection = JupyterConnection(config, signer)

        // Then: Should expose the signer
        assertThat(connection?.signer).isSameAs(signer)
    }

    private fun createTestConnectionFile(): ConnectionFile {
        // Use high ports to avoid conflicts
        return ConnectionFile.parse(
            """
            {
                "transport": "tcp",
                "ip": "127.0.0.1",
                "shell_port": 0,
                "iopub_port": 0,
                "control_port": 0,
                "stdin_port": 0,
                "hb_port": 0,
                "signature_scheme": "hmac-sha256",
                "key": "test-key"
            }
            """.trimIndent(),
        )
    }
}
