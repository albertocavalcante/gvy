package com.github.groovylsp.bsp.lifecycle

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.InitializeBuildParams
import com.github.groovylsp.bsp.client.BspCapabilities
import com.github.groovylsp.bsp.client.BuildServerConnection
import com.github.groovylsp.bsp.model.BuildTargetCache
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * BSP server discovery and connection manager, following the Metals pattern.
 *
 * This class handles:
 * - Discovering available BSP servers in .bsp directory
 * - Launching server processes
 * - Initializing BSP handshake
 * - Building complete BspSession with cache
 *
 * Usage:
 * ```
 * val connector = BspConnector(workspace)
 * val session = connector.connect().getOrThrow()
 * session.use { bsp ->
 *     // Use the session
 * }
 * ```
 */
class BspConnector(private val workspace: Path, private val config: BspConnectorConfig = BspConnectorConfig()) {
    private val logger = KotlinLogging.logger {}

    /**
     * Discover and connect to a BSP server.
     *
     * This will:
     * 1. Discover available BSP servers
     * 2. Select the first/configured server
     * 3. Launch the server process
     * 4. Initialize the connection
     * 5. Load build targets
     * 6. Return a ready-to-use BspSession
     *
     * @return Either a connection error or a ready BspSession
     */
    suspend fun connect(): Either<BspConnectionError, BspSession> {
        logger.info { "Connecting to BSP server in workspace: $workspace" }

        val details = selectServer() ?: run {
            logger.warn { "No BSP servers found in ${workspace.resolve(".bsp")}" }
            return BspConnectionError.NoServerFound(workspace).left()
        }

        logger.info { "Selected BSP server: ${details.name} v${details.version}" }

        return launchServer(details)
            .flatMap { (server, processClient) ->
                val (process, client) = processClient
                initializeSession(server, process, client, details)
            }
    }

    /**
     * Discover all available BSP servers in the workspace.
     *
     * @return List of discovered server details
     */
    fun discoverServers(): List<BspConnectionDetails> {
        logger.debug { "Discovering BSP servers in workspace: $workspace" }
        val servers = BspConnectionDetails.findAll(workspace)
        logger.info { "Discovered ${servers.size} BSP server(s)" }
        return servers
    }

    /**
     * Launch a BSP server process and create a connection.
     *
     * @param details Connection details for the server
     * @return Either an error or a triple of BuildServer, Process, and BspClientHandler
     */
    private suspend fun launchServer(
        details: BspConnectionDetails,
    ): Either<BspConnectionError, Pair<BuildServer, Pair<Process, BspClientHandler>>> {
        logger.info { "Launching BSP server: ${details.argv.joinToString(" ")}" }
        @Suppress("TooGenericExceptionCaught") // Process launch can fail with IOException, SecurityException, etc.
        val process = try {
            ProcessBuilder(details.argv)
                .directory(workspace.toFile())
                .redirectError(ProcessBuilder.Redirect.PIPE) // Capture stderr for logging
                .start()
        } catch (e: Exception) {
            logger.error(e) { "Failed to start BSP server process: ${e.message}" }
            return BspConnectionError.LaunchFailed(details.name, e).left()
        }

        // Monitor stderr in background for diagnostics
        CompletableFuture.runAsync {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logger.debug { "[BSP ${details.name} stderr] $line" }
                }
            }
        }

        val client = BspClientHandler()

        @Suppress("TooGenericExceptionCaught") // LSP4J launcher creation can fail with various exceptions
        val launcher = try {
            Launcher.Builder<BuildServer>()
                .setRemoteInterface(BuildServer::class.java)
                .setInput(process.inputStream)
                .setOutput(process.outputStream)
                .setLocalService(client)
                .create()
        } catch (e: Exception) {
            process.destroyForcibly()
            logger.error(e) { "Failed to create BSP launcher: ${e.message}" }
            return BspConnectionError.LaunchFailed(details.name, e).left()
        }

        val server = launcher.remoteProxy

        // Start listening for messages in background
        @Suppress("TooGenericExceptionCaught") // BSP protocol errors
        CompletableFuture.runAsync {
            try {
                launcher.startListening().get()
            } catch (e: Exception) {
                logger.error(e) { "BSP message listening failed: ${e.message}" }
            }
        }

        // Give the server a moment to start up
        // NOTE: Small delay to ensure server is ready for initialization
        //   Most servers are ready immediately, but some need a brief moment
        //   Ensure process cleanup if coroutine is cancelled during delay
        try {
            delay(config.serverStartupDelayMs)
        } catch (e: CancellationException) {
            logger.warn { "BSP connection cancelled during startup delay, cleaning up process" }
            process.destroyForcibly()
            throw e
        }

        logger.info { "BSP server process started (PID: ${process.pid()})" }

        // Note: BuildServerConnection will be created after initialization with capabilities
        return (server to Pair(process, client)).right()
    }

    private fun selectServer(): BspConnectionDetails? {
        val servers = discoverServers()
        if (servers.isEmpty()) return null

        // If a preferred server is configured, use it
        config.preferredServerName?.let { preferred ->
            servers.find { it.name.equals(preferred, ignoreCase = true) }?.let { server ->
                logger.info { "Using preferred server: ${server.name}" }
                return server
            }
            logger.warn { "Preferred server '$preferred' not found, using first available" }
        }

        // Use first server found
        return servers.first()
    }

    private suspend fun initializeSession(
        server: BuildServer,
        process: Process,
        client: BspClientHandler,
        details: BspConnectionDetails,
    ): Either<BspConnectionError, BspSession> {
        val params = InitializeBuildParams(
            config.clientName,
            config.clientVersion,
            config.bspVersion,
            workspace.toUri().toString(),
            BuildClientCapabilities(config.supportedLanguages),
        )
        @Suppress("TooGenericExceptionCaught") // BSP initialization failures: timeout, protocol mismatch, etc.
        return try {
            // Initialize the server
            val initResult = server.buildInitialize(params).await()
            server.onBuildInitialized()
            logger.info { "BSP initialized: ${initResult.displayName} v${initResult.version}" }

            // Create capabilities wrapper and connection
            val capabilities = BspCapabilities(initResult.capabilities)
            val connection = BuildServerConnection(server, capabilities)

            // Create session and load targets
            val cache = BuildTargetCache()
            val session = BspSession(connection, cache, client, process)

            // Load initial build targets
            session.reload()
                .mapLeft { error ->
                    session.close()
                    BspConnectionError.InitializationFailed(details.name, error.message)
                }
                .map { session }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize BSP server: ${e.message}" }
            process.destroyForcibly()
            BspConnectionError.InitializationFailed(details.name, e.message ?: "Unknown error").left()
        }
    }

    /**
     * Configuration for BSP connector behavior.
     */
    data class BspConnectorConfig(
        val clientName: String = "groovy-lsp",
        val clientVersion: String = "1.0.0",
        val bspVersion: String = "2.1.0",
        val supportedLanguages: List<String> = listOf("groovy", "java", "scala", "kotlin"),
        val preferredServerName: String? = null,
        val serverStartupDelayMs: Long = 100,
    )

    /**
     * Errors that can occur during BSP connection.
     */
    sealed class BspConnectionError(val message: String) {
        data class NoServerFound(val workspace: Path) :
            BspConnectionError("No BSP servers found in ${workspace.resolve(".bsp")}")

        data class LaunchFailed(val serverName: String, val cause: Throwable) :
            BspConnectionError("Failed to launch BSP server '$serverName': ${cause.message}")

        data class InitializationFailed(val serverName: String, val reason: String) :
            BspConnectionError("Failed to initialize BSP server '$serverName': $reason")

        override fun toString(): String = "BspConnectionError: $message"
    }
}
