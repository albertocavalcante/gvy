package com.github.albertocavalcante.groovyjupyter.zmq

import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import org.slf4j.LoggerFactory
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.io.Closeable

/**
 * Manages the ZMQ sockets for Jupyter communication.
 *
 * A Jupyter kernel communicates through 5 ZMQ sockets:
 * - Shell (ROUTER): Handles execute_request, kernel_info_request, etc.
 * - IOPub (PUB): Publishes stream output, execution results, status
 * - Control (ROUTER): Handles interrupt, shutdown (urgent messages)
 * - Stdin (ROUTER): Input requests (e.g., input() in Python/Groovy)
 * - Heartbeat (REP): Simple echo for liveness checking
 *
 * Inspired by kotlin-jupyter's socket architecture.
 */
class JupyterConnection(private val config: ConnectionFile, val signer: HmacSigner) : Closeable {
    private val logger = LoggerFactory.getLogger(JupyterConnection::class.java)
    private val context = ZContext()

    // Socket instances (created lazily on first access)
    val shellSocket: ZMQ.Socket by lazy { createSocket(ZMQ.ROUTER, "shell") }
    val iopubSocket: ZMQ.Socket by lazy { createSocket(ZMQ.PUB, "iopub") }
    val controlSocket: ZMQ.Socket by lazy { createSocket(ZMQ.ROUTER, "control") }
    val stdinSocket: ZMQ.Socket by lazy { createSocket(ZMQ.ROUTER, "stdin") }
    val heartbeatSocket: ZMQ.Socket by lazy { createSocket(ZMQ.REP, "heartbeat") }

    var isBound: Boolean = false
        private set

    var isClosed: Boolean = false
        private set

    /**
     * Bind all sockets to their configured ports.
     *
     * Uses port 0 in config to let ZMQ choose available ephemeral ports.
     * This is useful for testing. In production, Jupyter provides specific ports.
     */
    fun bind() {
        logger.info("Binding sockets...")

        bindSocket(shellSocket, config.shellAddress(), "shell")
        bindSocket(iopubSocket, config.iopubAddress(), "iopub")
        bindSocket(controlSocket, config.controlAddress(), "control")
        bindSocket(stdinSocket, config.stdinAddress(), "stdin")
        bindSocket(heartbeatSocket, config.heartbeatAddress(), "heartbeat")

        isBound = true
        logger.info("All sockets bound successfully")
    }

    override fun close() {
        if (isClosed) return

        logger.info("Closing sockets...")

        // Close sockets in reverse order of creation
        runCatching { heartbeatSocket.close() }
        runCatching { stdinSocket.close() }
        runCatching { controlSocket.close() }
        runCatching { iopubSocket.close() }
        runCatching { shellSocket.close() }

        context.close()
        isClosed = true
        logger.info("All sockets closed")
    }

    private fun createSocket(type: Int, name: String): ZMQ.Socket {
        logger.debug("Creating {} socket", name)
        return context.createSocket(type)
    }

    private fun bindSocket(socket: ZMQ.Socket, address: String, name: String) {
        // Port 0 means use ephemeral port - replace with wildcard for ZMQ
        val bindAddress = if (address.endsWith(":0")) {
            address.replace(":0", ":*")
        } else {
            address
        }

        logger.debug("Binding {} socket to {}", name, bindAddress)
        socket.bind(bindAddress)
        logger.info("Bound {} socket to {}", name, socket.lastEndpoint)
    }
}
