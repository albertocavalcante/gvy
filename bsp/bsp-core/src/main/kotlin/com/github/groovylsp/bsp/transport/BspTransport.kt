package com.github.groovylsp.bsp.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Base interface for BSP transport layer.
 *
 * Abstracts the underlying communication mechanism (stdio, socket, named pipe)
 * used to communicate with a BSP server. All transports provide:
 * - Input/output streams for JSON-RPC communication
 * - Connection status tracking
 * - Async connection establishment
 * - Resource cleanup via [Closeable]
 *
 * Implementations:
 * - [StdioTransport]: Process-based communication via stdin/stdout
 * - [SocketTransport]: Network socket or Unix domain socket communication
 */
interface BspTransport : Closeable {
    /**
     * Input stream to read JSON-RPC messages from the BSP server.
     */
    val inputStream: InputStream

    /**
     * Output stream to write JSON-RPC messages to the BSP server.
     */
    val outputStream: OutputStream

    /**
     * Whether the transport is currently connected and ready for communication.
     */
    val isConnected: Boolean

    /**
     * Suspends until the transport is fully connected and ready.
     * For some transports (e.g., stdio), this is a no-op.
     * For others (e.g., socket), this waits for the connection to establish.
     */
    suspend fun awaitConnection()
}
