package com.github.albertocavalcante.groovyjupyter.zmq

import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import org.slf4j.LoggerFactory
import org.zeromq.SocketType
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
    private val createdSockets = mutableListOf<ZMQ.Socket>()

    // Socket instances (created lazily on first access)
    val shellSocket: ZMQ.Socket by lazy { createSocket(SocketType.ROUTER, "shell") }
    val iopubSocket: ZMQ.Socket by lazy { createSocket(SocketType.PUB, "iopub") }
    val controlSocket: ZMQ.Socket by lazy { createSocket(SocketType.ROUTER, "control") }
    val stdinSocket: ZMQ.Socket by lazy { createSocket(SocketType.ROUTER, "stdin") }
    val heartbeatSocket: ZMQ.Socket by lazy { createSocket(SocketType.REP, "heartbeat") }

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
        createdSockets.asReversed().forEach { runCatching { it.close() } }
        createdSockets.clear()

        context.close()
        isClosed = true
        logger.info("All sockets closed")
    }

    private fun createSocket(type: SocketType, name: String): ZMQ.Socket {
        logger.debug("Creating {} socket", name)
        return context.createSocket(type).also { createdSockets.add(it) }
    }

    /**
     * Create a ZMQ poller for monitoring sockets.
     */
    fun createPoller(size: Int): ZMQ.Poller = context.createPoller(size)

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

    /**
     * Send a Jupyter message.
     *
     * Signing is handled automatically.
     */

    /**
     * Send a Jupyter message.
     *
     * Signing is handled automatically.
     * Defaults to SHELL socket.
     */
    fun sendMessage(
        message: com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage,
        socket: ZMQ.Socket = shellSocket,
    ) {
        val wireMessage = com.github.albertocavalcante.groovyjupyter.zmq.WireMessage(
            identities = message.identities,
            signature = "", // Will be computed by toSignedFrames
            header = message.header.toJson(),
            parentHeader = message.parentHeader?.toJson() ?: "{}",
            metadata = message.metadataToJson(),
            content = message.contentToJson(),
        )

        val frames = wireMessage.toSignedFrames(signer)

        frames.forEachIndexed { index, frame ->
            if (index < frames.size - 1) {
                socket.sendMore(frame)
            } else {
                socket.send(frame)
            }
        }
    }

    /**
     * Send a message specifically to the IOPub socket.
     *
     * IOPub messages (like stream, status, execute_result) are PUBLISHED to all subscribers.
     * They do not have identities (Router/Dealer) prefixed when sending from PUB socket?
     * Wait, ZMQ PUB sockets just send content. Subscribers filter by topic if they want.
     * Jupyter Protocol says:
     * IOPub messages are:
     * [topic, "<IDS|MSG>", signature, header, parent_header, metadata, content]
     *
     * The topic is usually "kernel_id" or a uuid.
     * BUT, standard Jupyter kernels often just publish the message.
     * Let's check `WireMessage` handling.
     * If we use `sendMessage` with `iopubSocket`, it sends:
     * [identities..., <IDS|MSG>, signature, ...]
     *
     * For PUB/SUB:
     * The "identities" part acts as the TOPIC if it's the first frame.
     * Jupyter expects the topic to be `kernel_id` or related.
     *
     * However, WireMessage structure puts identities BEFORE delimiter.
     * For IOPub, we should construct it such that the first frame is the topic.
     */
    fun sendIOPubMessage(message: com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage) {
        // Topic usually: "kernel._uuid_.msg_type"
        // Or just "kernel_id"
        // Let's use the message type as topic or just empty?
        // Actually, many kernels use "kernel.<uuid>"
        val topic = "kernel.${message.header.session}" // Session is often used roughly as ID context

        // WireMessage expects a list of identities. We can abuse this to send the topic.
        // If we set identities = ["topic".bytes], it will be sent as:
        // topic, <IDS|MSG>, ...
        // Which is exactly what we want for IOPub!

        val iopubMessage = message.copy(
            identities = mutableListOf(topic.toByteArray(Charsets.UTF_8)),
        )

        sendMessage(iopubMessage, iopubSocket)
    }
}
