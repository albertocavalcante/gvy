// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jupyter.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Connection configuration passed by Jupyter to the kernel.
 *
 * When Jupyter starts a kernel, it passes the path to a JSON file containing
 * the ZMQ port numbers, transport type, and HMAC key for message signing.
 *
 * @see <a href="https://jupyter-client.readthedocs.io/en/stable/kernels.html#connection-files">
 *     Jupyter Connection Files</a>
 */
@Serializable
data class ConnectionFile(
    val transport: String,
    val ip: String,
    @SerialName("control_port") val controlPort: Int,
    @SerialName("shell_port") val shellPort: Int,
    @SerialName("stdin_port") val stdinPort: Int,
    @SerialName("hb_port") val heartbeatPort: Int,
    @SerialName("iopub_port") val iopubPort: Int,
    @SerialName("signature_scheme") val signatureScheme: String,
    val key: String,
) {
    /**
     * Check if HMAC message signing is enabled.
     */
    fun isSigningEnabled(): Boolean = key.isNotEmpty()

    /**
     * Fail fast when the connection file asks for a signature scheme this kernel cannot verify.
     *
     * Only `hmac-sha256` is implemented. Silently signing/verifying with SHA-256 under another declared
     * scheme would make every message from the frontend fail authentication (or, worse, hide the mismatch).
     * Unsigned connections (empty key) ignore the scheme, as in the Jupyter protocol.
     */
    fun requireSupportedSignatureScheme() {
        require(!isSigningEnabled() || signatureScheme == SUPPORTED_SIGNATURE_SCHEME) {
            "Unsupported signature_scheme '$signatureScheme'; only '$SUPPORTED_SIGNATURE_SCHEME' is supported"
        }
    }

    /**
     * Build ZMQ address for shell socket.
     */
    fun shellAddress(): String = "$transport://$ip:$shellPort"

    /**
     * Build ZMQ address for IOPub socket.
     */
    fun iopubAddress(): String = "$transport://$ip:$iopubPort"

    /**
     * Build ZMQ address for control socket.
     */
    fun controlAddress(): String = "$transport://$ip:$controlPort"

    /**
     * Build ZMQ address for stdin socket.
     */
    fun stdinAddress(): String = "$transport://$ip:$stdinPort"

    /**
     * Build ZMQ address for heartbeat socket.
     */
    fun heartbeatAddress(): String = "$transport://$ip:$heartbeatPort"

    companion object {
        const val SUPPORTED_SIGNATURE_SCHEME = "hmac-sha256"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse connection file from JSON string.
         */
        fun parse(jsonString: String): ConnectionFile = json.decodeFromString(ConnectionFile.serializer(), jsonString)

        /**
         * Parse connection file from file path.
         */
        fun fromPath(path: Path): ConnectionFile = parse(path.toFile().readText())
    }
}
