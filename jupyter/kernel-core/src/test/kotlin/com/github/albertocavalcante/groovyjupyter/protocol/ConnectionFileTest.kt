package com.github.albertocavalcante.groovyjupyter.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * TDD tests for ConnectionFile parsing.
 *
 * The ConnectionFile is JSON passed by Jupyter to the kernel on startup,
 * containing port numbers and security configuration.
 */
class ConnectionFileTest {

    @Test
    fun `should parse connection file from JSON string`() {
        // Given: A typical Jupyter connection file JSON
        val json = """
            {
                "transport": "tcp",
                "ip": "127.0.0.1",
                "control_port": 50160,
                "shell_port": 57503,
                "stdin_port": 52597,
                "hb_port": 42540,
                "iopub_port": 40885,
                "signature_scheme": "hmac-sha256",
                "key": "a0436f6c-1916-498b-8eb9-e81ab9368e84"
            }
        """.trimIndent()

        // When: Parsing the connection file
        val config = ConnectionFile.parse(json)

        // Then: All fields should be extracted correctly
        assertThat(config.transport).isEqualTo("tcp")
        assertThat(config.ip).isEqualTo("127.0.0.1")
        assertThat(config.controlPort).isEqualTo(50160)
        assertThat(config.shellPort).isEqualTo(57503)
        assertThat(config.stdinPort).isEqualTo(52597)
        assertThat(config.heartbeatPort).isEqualTo(42540)
        assertThat(config.iopubPort).isEqualTo(40885)
        assertThat(config.signatureScheme).isEqualTo("hmac-sha256")
        assertThat(config.key).isEqualTo("a0436f6c-1916-498b-8eb9-e81ab9368e84")
    }

    @Test
    fun `should parse connection file from file path`(@TempDir tempDir: Path) {
        // Given: A connection file on disk
        val json = """
            {
                "transport": "tcp",
                "ip": "localhost",
                "control_port": 1111,
                "shell_port": 2222,
                "stdin_port": 3333,
                "hb_port": 4444,
                "iopub_port": 5555,
                "signature_scheme": "hmac-sha256",
                "key": "test-key"
            }
        """.trimIndent()

        val connectionFile = tempDir.resolve("connection.json")
        connectionFile.toFile().writeText(json)

        // When: Parsing from file path
        val config = ConnectionFile.fromPath(connectionFile)

        // Then: Fields should be parsed
        assertThat(config.ip).isEqualTo("localhost")
        assertThat(config.shellPort).isEqualTo(2222)
    }

    @Test
    fun `should build ZMQ address from transport and port`() {
        // Given: A parsed connection file
        val json = """
            {
                "transport": "tcp",
                "ip": "127.0.0.1",
                "control_port": 50160,
                "shell_port": 57503,
                "stdin_port": 52597,
                "hb_port": 42540,
                "iopub_port": 40885,
                "signature_scheme": "hmac-sha256",
                "key": "test-key"
            }
        """.trimIndent()
        val config = ConnectionFile.parse(json)

        // When: Building socket addresses
        // Then: Should produce valid ZMQ addresses
        assertThat(config.shellAddress()).isEqualTo("tcp://127.0.0.1:57503")
        assertThat(config.iopubAddress()).isEqualTo("tcp://127.0.0.1:40885")
        assertThat(config.controlAddress()).isEqualTo("tcp://127.0.0.1:50160")
        assertThat(config.stdinAddress()).isEqualTo("tcp://127.0.0.1:52597")
        assertThat(config.heartbeatAddress()).isEqualTo("tcp://127.0.0.1:42540")
    }

    @Test
    fun `should handle empty key for unsigned messages`() {
        // Given: A connection file with empty key (no signing)
        val json = """
            {
                "transport": "tcp",
                "ip": "127.0.0.1",
                "control_port": 1,
                "shell_port": 2,
                "stdin_port": 3,
                "hb_port": 4,
                "iopub_port": 5,
                "signature_scheme": "",
                "key": ""
            }
        """.trimIndent()

        // When: Parsing
        val config = ConnectionFile.parse(json)

        // Then: Key should be empty and signing disabled
        assertThat(config.key).isEmpty()
        assertThat(config.isSigningEnabled()).isFalse()
    }

    @Test
    fun `should detect when signing is enabled`() {
        // Given: A connection file with a key
        val json = """
            {
                "transport": "tcp",
                "ip": "127.0.0.1",
                "control_port": 1,
                "shell_port": 2,
                "stdin_port": 3,
                "hb_port": 4,
                "iopub_port": 5,
                "signature_scheme": "hmac-sha256",
                "key": "secret-key"
            }
        """.trimIndent()

        // When: Parsing
        val config = ConnectionFile.parse(json)

        // Then: Signing should be enabled
        assertThat(config.isSigningEnabled()).isTrue()
    }
}
