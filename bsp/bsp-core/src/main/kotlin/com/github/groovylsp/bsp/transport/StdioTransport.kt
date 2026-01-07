package com.github.groovylsp.bsp.transport

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * Process-based BSP transport using stdin/stdout communication.
 *
 * This transport launches a BSP server as a child process and communicates
 * via standard streams:
 * - [inputStream]: reads from process stdout (server messages)
 * - [outputStream]: writes to process stdin (client messages)
 * - [errorStream]: reads from process stderr (diagnostic logs)
 *
 * The transport is considered connected as long as the process is alive.
 * Closing the transport destroys the child process.
 *
 * Example:
 * ```kotlin
 * val transport = StdioTransport.launch(
 *     argv = listOf("bsp-server", "--stdio"),
 *     workingDir = Paths.get("/workspace")
 * )
 * // Use transport.inputStream and transport.outputStream for JSON-RPC
 * transport.close()  // Terminates the process
 * ```
 */
class StdioTransport private constructor(private val process: Process) : BspTransport {
    override val inputStream: InputStream
        get() = process.inputStream

    override val outputStream: OutputStream
        get() = process.outputStream

    override val isConnected: Boolean
        get() = process.isAlive

    /**
     * Stream for reading diagnostic messages from the server's stderr.
     * Typically used with [ErrorStreamLogger] for async logging.
     */
    val errorStream: InputStream
        get() = process.errorStream

    /**
     * No-op for stdio transport - the process is immediately ready after launch.
     */
    override suspend fun awaitConnection() {
        // Stdio transport is ready immediately after process launch
    }

    /**
     * Terminates the BSP server process.
     * First attempts graceful shutdown, then forcefully destroys if needed.
     */
    override fun close() {
        if (process.isAlive) {
            logger.debug("Closing stdio transport, terminating process")
            process.destroy()

            // NOTE: Not waiting for exit here to avoid blocking.
            // If graceful shutdown is needed, the client should send shutdown
            // requests before closing the transport.
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StdioTransport::class.java)

        /**
         * Launches a BSP server process and returns a stdio transport.
         *
         * @param argv Command and arguments to launch the BSP server
         * @param workingDir Working directory for the process
         * @param environment Additional environment variables (default: inherit parent)
         * @return StdioTransport connected to the launched process
         * @throws java.io.IOException if process launch fails
         */
        fun launch(
            argv: List<String>,
            workingDir: Path,
            environment: Map<String, String> = emptyMap(),
        ): StdioTransport {
            require(argv.isNotEmpty()) { "argv must not be empty" }

            logger.info("Launching BSP server: ${argv.joinToString(" ")}")
            logger.debug("Working directory: $workingDir")

            val process = ProcessLauncher.launch(argv, workingDir, environment)
            return StdioTransport(process)
        }
    }
}
