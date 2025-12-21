package com.github.albertocavalcante.groovyjupyter.kernel

import com.github.albertocavalcante.groovyjupyter.execution.GroovyExecutor
import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteHandler
import com.github.albertocavalcante.groovyjupyter.handlers.HeartbeatHandler
import com.github.albertocavalcante.groovyjupyter.handlers.KernelInfoHandler
import com.github.albertocavalcante.groovyjupyter.handlers.MessageHandler
import com.github.albertocavalcante.groovyjupyter.handlers.ShutdownHandler
import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import org.slf4j.LoggerFactory
import org.zeromq.ZMQ
import java.io.Closeable

/**
 * Main kernel server that polls sockets and dispatches messages.
 *
 * Uses ZMQ.Poller to monitor Shell, Control, and Heartbeat sockets,
 * dispatching incoming messages to appropriate handlers.
 */
class KernelServer(
    private val connection: JupyterConnection,
    val handlers: List<MessageHandler>,
    private val heartbeatHandler: HeartbeatHandler,
) : Closeable {
    private val logger = LoggerFactory.getLogger(KernelServer::class.java)

    @Volatile
    var isRunning: Boolean = true
        private set

    /**
     * Find a handler that can process the given message type.
     */
    fun findHandler(msgType: MessageType): MessageHandler? = handlers.find { it.canHandle(msgType) }

    /**
     * Run the main polling loop.
     *
     * Polls Shell, Control, and Heartbeat sockets and dispatches messages.
     */
    fun run() {
        logger.info("Starting kernel server main loop")

        connection.bind()

        val poller = connection.createPoller(SOCKET_COUNT)
        poller.register(connection.shellSocket, ZMQ.Poller.POLLIN)
        poller.register(connection.controlSocket, ZMQ.Poller.POLLIN)
        poller.register(connection.heartbeatSocket, ZMQ.Poller.POLLIN)

        @Suppress("LoopWithTooManyJumpStatements")
        while (isRunning) {
            val pollResult = poller.poll(POLL_TIMEOUT_MS)

            if (pollResult == -1) {
                if (!isRunning) break // Shutdown requested
                continue
            }

            // Handle heartbeat (highest priority for liveness)
            if (poller.pollin(HEARTBEAT_INDEX)) {
                heartbeatHandler.handleOnce()
            }

            // Handle control messages (shutdown, interrupt)
            if (poller.pollin(CONTROL_INDEX)) {
                handleSocketMessage(connection.controlSocket, "control")
            }

            // Handle shell messages (execute, kernel_info)
            if (poller.pollin(SHELL_INDEX)) {
                handleSocketMessage(connection.shellSocket, "shell")
            }
        }

        poller.close()
        logger.info("Kernel server stopped")
    }

    /**
     * Stop the server gracefully.
     */
    fun shutdown() {
        logger.info("Shutdown requested")
        isRunning = false
    }

    override fun close() {
        shutdown()
        connection.close()
    }

    private fun handleSocketMessage(socket: ZMQ.Socket, socketName: String) {
        logger.debug("Received message on {} socket", socketName)

        // TODO: Parse WireMessage, find handler, dispatch
        // For now, just receive and discard
        val frames = mutableListOf<ByteArray>()
        var more = true
        while (more) {
            val frame = socket.recv(ZMQ.DONTWAIT) ?: break
            frames.add(frame)
            more = socket.hasReceiveMore()
        }

        if (frames.isNotEmpty()) {
            logger.debug("Received {} frames on {}", frames.size, socketName)
        }
    }

    companion object {
        private const val SOCKET_COUNT = 3
        private const val POLL_TIMEOUT_MS = 100L
        private const val SHELL_INDEX = 0
        private const val CONTROL_INDEX = 1
        private const val HEARTBEAT_INDEX = 2

        /**
         * Create a KernelServer with standard handlers.
         */
        fun create(config: ConnectionFile, signer: HmacSigner, executor: GroovyExecutor): KernelServer {
            val connection = JupyterConnection(config, signer)
            val heartbeatHandler = HeartbeatHandler(connection.heartbeatSocket)

            lateinit var server: KernelServer

            val handlers = listOf(
                KernelInfoHandler(),
                ExecuteHandler(executor),
                ShutdownHandler { server.shutdown() },
            )

            server = KernelServer(connection, handlers, heartbeatHandler)
            return server
        }
    }
}
