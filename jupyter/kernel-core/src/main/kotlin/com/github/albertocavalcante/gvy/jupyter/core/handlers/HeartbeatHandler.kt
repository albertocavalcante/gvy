package com.github.albertocavalcante.gvy.jupyter.core.handlers

import io.github.oshai.kotlinlogging.KotlinLogging
import org.zeromq.ZMQ

/**
 * Handles heartbeat messages for kernel liveness checking.
 *
 * The heartbeat socket uses a simple REQ-REP pattern:
 * 1. Jupyter frontend sends a ping message
 * 2. Kernel echoes the exact message back
 * 3. If no response within timeout, kernel is considered dead
 *
 * This is the simplest handler - it just echoes bytes unchanged.
 */
class HeartbeatHandler(private val socket: ZMQ.Socket) {
    private val logger = KotlinLogging.logger {}

    /**
     * Handle one heartbeat message if available.
     *
     * Uses non-blocking receive to check for pending messages.
     *
     * @return true if a message was handled, false if no message was available
     */
    fun handleOnce(): Boolean {
        val message = socket.recv(ZMQ.DONTWAIT) ?: return false

        logger.trace { "Heartbeat received: ${message.size} bytes" }
        socket.send(message)
        logger.trace { "Heartbeat echoed" }

        return true
    }

    /**
     * Block until a heartbeat is received and echo it.
     *
     * Use this when running in a dedicated heartbeat thread.
     */
    fun handleBlocking() {
        val message = socket.recv(0) // Blocking receive
        socket.send(message)
        logger.trace { "Heartbeat handled (blocking)" }
    }
}
