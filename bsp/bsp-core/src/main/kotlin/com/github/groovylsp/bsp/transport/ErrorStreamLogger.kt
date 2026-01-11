package com.github.groovylsp.bsp.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Asynchronous logger for BSP server stderr streams.
 *
 * Reads diagnostic messages from a server's error stream and logs them
 * using SLF4J. This prevents stderr from blocking the process and ensures
 * diagnostic information is captured in the client's logs.
 *
 * The logger runs in a background coroutine and stops when:
 * - The error stream is closed (server process terminates)
 * - The coroutine scope is cancelled
 *
 * Example:
 * ```kotlin
 * val transport = StdioTransport.launch(...)
 * val logger = ErrorStreamLogger(
 *     errorStream = transport.errorStream,
 *     serverName = "gradle-bsp",
 *     scope = clientScope
 * )
 * val job = logger.startLogging()
 * // ... use transport ...
 * job.cancel()  // Stop logging when done
 * ```
 */
class ErrorStreamLogger(
    private val errorStream: InputStream,
    private val serverName: String,
    private val scope: CoroutineScope,
) {
    private val logger = KotlinLogging.logger("BSP.$serverName")

    /**
     * Starts asynchronously reading and logging stderr messages.
     *
     * Creates a background coroutine that:
     * 1. Reads lines from [errorStream]
     * 2. Logs each line at WARN level (server diagnostics are usually warnings/errors)
     * 3. Stops when the stream ends or scope is cancelled
     *
     * @return Job representing the logging coroutine
     */
    fun startLogging(): Job = scope.launch(Dispatchers.IO) {
        logger.debug { "Started stderr logging for BSP server '$serverName'" }

        try {
            BufferedReader(InputStreamReader(errorStream)).use { reader ->
                var lineNumber = 0
                reader.lineSequence().forEach { line ->
                    lineNumber++
                    // NOTE: Using WARN level because stderr typically contains
                    // diagnostics, warnings, or errors from the server
                    logger.warn { "[stderr:$lineNumber] $line" }
                }
            }
        } catch (e: Exception) {
            // NOTE: IOException is expected when stream closes normally
            // Only log unexpected exceptions
            if (e !is java.io.IOException) {
                logger.error(e) { "Error while reading stderr: ${e.message}" }
            } else {
                logger.debug { "Stderr stream closed" }
            }
        } finally {
            logger.debug { "Stopped stderr logging for BSP server '$serverName'" }
        }
    }

    companion object {
        /**
         * Convenience method to create and start logging in one call.
         *
         * @param errorStream Stream to read from (typically [StdioTransport.errorStream])
         * @param serverName Server identifier for log categorization
         * @param scope Coroutine scope for the logging job
         * @return Pair of logger instance and the active logging job
         */
        fun createAndStart(
            errorStream: InputStream,
            serverName: String,
            scope: CoroutineScope,
        ): Pair<ErrorStreamLogger, Job> {
            val logger = ErrorStreamLogger(errorStream, serverName, scope)
            val job = logger.startLogging()
            return logger to job
        }
    }
}
