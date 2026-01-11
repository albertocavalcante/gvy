package com.github.groovylsp.bsp.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Socket-based BSP transport for network or Unix domain socket communication.
 *
 * Supports two connection modes:
 * 1. TCP sockets: [connect] to a BSP server via host:port
 * 2. Unix domain sockets: [connectUnixSocket] to a BSP server via file path
 *
 * The transport provides streams for JSON-RPC communication over the established
 * socket connection. Connection attempts honor the provided timeout.
 *
 * Example (TCP):
 * ```kotlin
 * val transport = SocketTransport.connect(
 *     host = "localhost",
 *     port = 5037,
 *     timeout = 10.seconds
 * )
 * ```
 *
 * Example (Unix domain socket):
 * ```kotlin
 * val transport = SocketTransport.connectUnixSocket(
 *     path = Paths.get("/tmp/bsp.sock"),
 *     timeout = 10.seconds
 * )
 * ```
 */
class SocketTransport private constructor(private val socket: Socket) : BspTransport {
    override val inputStream: InputStream
        get() = socket.getInputStream()

    override val outputStream: OutputStream
        get() = socket.getOutputStream()

    override val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed

    /**
     * No-op for socket transport - the connection is already established.
     */
    override suspend fun awaitConnection() {
        // Socket is already connected when this transport is created
    }

    /**
     * Closes the underlying socket connection.
     */
    override fun close() {
        if (!socket.isClosed) {
            logger.debug { "Closing socket transport" }
            socket.close()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Connects to a BSP server via TCP socket.
         *
         * @param host Server hostname or IP address
         * @param port Server port number
         * @param timeout Maximum time to wait for connection
         * @return SocketTransport connected to the server
         * @throws IOException if connection fails
         * @throws kotlinx.coroutines.TimeoutCancellationException if timeout is exceeded
         */
        suspend fun connect(host: String, port: Int, timeout: Duration): SocketTransport = withContext(Dispatchers.IO) {
            require(port in 1..65535) { "Port must be in range 1-65535" }

            logger.info { "Connecting to BSP server at $host:$port (timeout: $timeout)" }

            val socket = withTimeout(timeout) {
                Socket(host, port).also {
                    // NOTE: TCP_NODELAY reduces latency for small JSON-RPC messages
                    it.tcpNoDelay = true
                }
            }

            logger.info { "Successfully connected to $host:$port" }
            SocketTransport(socket)
        }

        /**
         * Connects to a BSP server via Unix domain socket.
         *
         * Unix domain sockets provide local IPC with lower overhead than TCP.
         * Requires Java 16+ for Unix domain socket support.
         *
         * @param path File path to the Unix domain socket
         * @param timeout Maximum time to wait for connection
         * @return SocketTransport connected to the server
         * @throws IOException if connection fails
         * @throws kotlinx.coroutines.TimeoutCancellationException if timeout is exceeded
         * @throws UnsupportedOperationException if Unix domain sockets are not supported
         */
        suspend fun connectUnixSocket(path: Path, timeout: Duration): SocketTransport = withContext(Dispatchers.IO) {
            logger.info { "Connecting to BSP server at Unix socket: $path (timeout: $timeout)" }

            // NOTE: Java 16+ required for Unix domain socket support
            val socket = withTimeout(timeout) {
                try {
                    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                    channel.connect(UnixDomainSocketAddress.of(path))
                    channel.socket()
                } catch (e: UnsupportedOperationException) {
                    throw UnsupportedOperationException(
                        "Unix domain sockets require Java 16+",
                        e,
                    )
                }
            }

            logger.info { "Successfully connected to Unix socket: $path" }
            SocketTransport(socket)
        }
    }
}
