package com.github.groovylsp.bsp.transport

import com.github.groovylsp.bsp.lifecycle.BspConnectionDetails
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/**
 * Helper for launching BSP server processes.
 *
 * Handles the low-level details of process creation:
 * - Working directory configuration
 * - Environment variable injection
 * - Process builder setup
 *
 * Typically used via [StdioTransport.launch], but can be used directly
 * for custom process management scenarios.
 */
object ProcessLauncher {
    private val logger = KotlinLogging.logger {}

    /**
     * Launches a process with the given command and configuration.
     *
     * @param argv Command and arguments (first element is the executable)
     * @param workingDir Working directory for the process
     * @param environment Additional environment variables to set (merged with parent env)
     * @return The launched Process instance
     * @throws IOException if process launch fails
     * @throws IllegalArgumentException if argv is empty
     */
    fun launch(argv: List<String>, workingDir: Path, environment: Map<String, String> = emptyMap()): Process {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        logger.debug { "Launching process: ${argv.joinToString(" ")}" }
        logger.debug { "Working directory: $workingDir" }

        if (environment.isNotEmpty()) {
            logger.debug { "Environment overrides: ${environment.keys}" }
        }

        val processBuilder = ProcessBuilder(argv).apply {
            directory(workingDir.toFile())

            // Merge parent environment with provided overrides
            if (environment.isNotEmpty()) {
                environment().putAll(environment)
            }

            // NOTE: Do not redirect error stream - we want separate stderr handling
            // via ErrorStreamLogger for proper diagnostics
            redirectErrorStream(false)
        }

        return try {
            processBuilder.start().also {
                logger.info { "Process launched successfully (pid: ${it.pid()})" }
            }
        } catch (e: IOException) {
            logger.error(e) { "Failed to launch process: ${e.message}" }
            throw IOException("Failed to launch BSP server: ${e.message}", e)
        }
    }

    /**
     * Launches a BSP server based on connection details and creates a transport.
     *
     * This is the primary entry point for BSP client connections. It:
     * 1. Resolves the server command from [BspConnectionDetails.argv]
     * 2. Launches the server process in the workspace directory
     * 3. Returns a [StdioTransport] for communication
     *
     * @param details BSP connection configuration (from .bsp/ JSON files)
     * @param workingDir Workspace root directory
     * @param environment Additional environment variables (optional)
     * @return BspTransport connected to the launched server
     * @throws IOException if process launch fails
     */
    suspend fun launchAndConnect(
        details: BspConnectionDetails,
        workingDir: Path,
        environment: Map<String, String> = emptyMap(),
    ): BspTransport = withContext(Dispatchers.IO) {
        logger.info { "Launching BSP server '${details.name}' v${details.version}" }
        logger.debug { "BSP version: ${details.bspVersion}, languages: ${details.languages}" }

        StdioTransport.launch(
            argv = details.argv,
            workingDir = workingDir,
            environment = environment,
        )
    }
}
