package com.github.albertocavalcante.groovylsp.e2e

import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import com.github.albertocavalcante.groovylsp.services.GroovyLanguageClient
import com.github.albertocavalcante.groovylsp.testing.api.GroovyLanguageServerProtocol
import com.github.albertocavalcante.groovylsp.testing.client.HarnessLanguageClient
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class LanguageServerSessionFactory {
    private val logger = LoggerFactory.getLogger(LanguageServerSessionFactory::class.java)

    // Cached server executor for in-process mode to allow cleanly shutting down the thread
    private val inProcessExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "groovy-lsp-in-process-server").apply { isDaemon = true }
    }

    private val execJar: Path? = System.getProperty("groovy.lsp.e2e.execJar")
        ?.let { Path.of(it) }
        ?.takeIf { Files.exists(it) }

    private val serverClasspath: String? = System.getProperty("groovy.lsp.e2e.serverClasspath")
    private val mainClass: String? = System.getProperty("groovy.lsp.e2e.mainClass")
    private val gradleUserHome: Path? = resolveGradleUserHome()

    fun start(serverConfig: ServerConfig, scenarioName: String): LanguageServerSession {
        val launchMode = serverConfig.mode

        return if (launchMode == ServerLaunchMode.InProcess) {
            createInProcessSession(scenarioName)
        } else {
            createProcessSession(serverConfig, scenarioName, launchMode)
        }
    }

    private fun createProcessSession(
        serverConfig: ServerConfig,
        scenarioName: String,
        launchMode: ServerLaunchMode,
    ): LanguageServerSession {
        require(launchMode == ServerLaunchMode.Stdio) {
            "Only stdio launch mode is currently supported (requested: $launchMode) for scenario '$scenarioName'"
        }

        val command = buildCommand(launchMode)
        logger.info(
            "Starting language server for scenario '{}' using command: {}",
            scenarioName,
            command.joinToString(" "),
        )

        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(false)

        gradleUserHome?.let {
            processBuilder.environment()["GRADLE_USER_HOME"] = it.toAbsolutePath().toString()
            logger.info("Using isolated Gradle user home for scenario '{}': {}", scenarioName, it)
        }

        val process = processBuilder.start()

        val client = HarnessLanguageClient()

        // Use Launcher.Builder with GroovyLanguageServerProtocol to properly deserialize
        // custom @JsonRequest method responses. LSPLauncher.createClientLauncher only
        // creates a proxy for the standard LanguageServer interface, causing
        // endpoint.request() to return null for custom methods like groovy/discoverTests.
        //
        // @see https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol
        val launcher = Launcher.Builder<GroovyLanguageServerProtocol>()
            .setLocalService(client)
            .setRemoteInterface(GroovyLanguageServerProtocol::class.java)
            .setInput(process.inputStream)
            .setOutput(process.outputStream)
            .create()
        val listening = launcher.startListening()

        val stderrPump = startErrorPump(process, scenarioName)

        return LanguageServerSession(
            process = process,
            server = launcher.remoteProxy,
            endpoint = launcher.remoteEndpoint,
            client = client,
            listening = listening,
            stderrPump = stderrPump,
            serverFuture = null,
            typedServerProxy = launcher.remoteProxy,
        )
    }

    private fun buildCommand(mode: ServerLaunchMode): List<String> {
        val launchArgs = when {
            serverClasspath != null && mainClass != null -> listOf("java", "-cp", serverClasspath, mainClass)
            execJar != null -> listOf("java", "-jar", execJar.toString())
            else -> error(
                "Unable to locate language server executable; expected either groovy.lsp.e2e.execJar or " +
                    "(groovy.lsp.e2e.serverClasspath & groovy.lsp.e2e.mainClass) system properties",
            )
        }

        return launchArgs + when (mode) {
            ServerLaunchMode.Stdio -> listOf("stdio")
            ServerLaunchMode.Socket -> error("Socket mode is not yet implemented in the e2e harness")
            ServerLaunchMode.InProcess -> error("In-process mode should not invoke buildCommand")
        }
    }

    private fun resolveGradleUserHome(): Path? {
        val override = System.getProperty("groovy.lsp.e2e.gradleUserHome")
            ?: System.getenv("GROOVY_LSP_E2E_GRADLE_USER_HOME")

        // Use unique suffix per JVM to support parallel test execution.
        // Multiple test forks can safely share the parent directory (Gradle handles locking),
        // but isolating prevents any potential race conditions during heavy parallel load.
        val uniqueSuffix = ProcessHandle.current().pid()
        val target = when {
            !override.isNullOrBlank() -> Paths.get(override)
            else -> Paths.get("").toAbsolutePath().resolve("build/e2e-gradle-home-$uniqueSuffix")
        }

        return runCatching {
            Files.createDirectories(target)
            target
        }.onFailure {
            logger.warn("Failed to prepare isolated Gradle user home at {}: {}", target, it.message)
        }.getOrNull()
    }

    private fun createInProcessSession(scenarioName: String): LanguageServerSession {
        val (clientIn, serverOut) = createPipePair()
        val (serverIn, clientOut) = createPipePair()

        val server = GroovyLanguageServer()

        val serverLauncher = Launcher.Builder<GroovyLanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(GroovyLanguageClient::class.java)
            .setInput(serverIn)
            .setOutput(serverOut)
            .create()

        server.connect(serverLauncher.remoteProxy)

        // Start the server's JSON-RPC listener in a background thread
        val serverListening = inProcessExecutor.submit {
            try {
                serverLauncher.startListening().get()
            } catch (e: Exception) {
                logger.error("In-process server error for scenario '{}'", scenarioName, e)
            }
        }

        // Create the client-side launcher with typed GroovyLanguageServerProtocol interface.
        // This enables proper deserialization of custom @JsonRequest method responses.
        // @see https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol
        val client = HarnessLanguageClient()
        val clientLauncher = Launcher.Builder<GroovyLanguageServerProtocol>()
            .setLocalService(client)
            .setRemoteInterface(GroovyLanguageServerProtocol::class.java)
            .setInput(clientIn)
            .setOutput(clientOut)
            .create()
        val clientListening = clientLauncher.startListening()

        // MINIMAL WAIT: Piped I/O synchronization for LSP4J launchers.
        //
        // This brief pause allows the piped streams to stabilize after startListening().
        // The server-side launcher creates a RemoteProxy for the client asynchronously,
        // and without this wait, early messages may be lost.
        //
        // Note: This is NOT the server readiness wait. For proper E2E test synchronization
        // with server initialization, tests should wait for client.readyFuture after calling
        // the `initialized` step. See: https://github.com/albertocavalcante/groovy-lsp/issues/314
        //
        // This small delay is acceptable because:
        // 1. It's only for in-process testing (not production)
        // 2. It's minimal (50ms) compared to typical test scenarios
        // 3. The alternative (polling/handshake) adds significant complexity for marginal benefit
        Thread.sleep(50)

        // Force proxy creation by accessing it - this triggers LSP4J to complete setup
        try {
            serverLauncher.remoteProxy // Force proxy creation
        } catch (e: Exception) {
            logger.warn("Could not access server's remote proxy: {}", e.message)
        }

        logger.info("Started in-process language server for scenario '{}'", scenarioName)

        return LanguageServerSession(
            process = null,
            server = clientLauncher.remoteProxy,
            endpoint = clientLauncher.remoteEndpoint,
            client = client,
            listening = clientListening, // We track client's listener; server's is managed by executor
            stderrPump = null,
            serverFuture = serverListening,
            typedServerProxy = clientLauncher.remoteProxy,
        )
    }

    private fun createPipePair(): Pair<PipedInputStream, PipedOutputStream> {
        val output = PipedOutputStream()
        val input = PipedInputStream()
        input.connect(output)
        return input to output
    }

    private fun startErrorPump(process: Process, scenarioName: String): Thread {
        val thread = Thread(
            {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        logger.info("[server:{}] {}", scenarioName, line)
                        line = reader.readLine()
                    }
                }
            },
            "groovy-lsp-e2e-stderr",
        )
        thread.isDaemon = true
        thread.start()
        return thread
    }
}

/**
 * Represents an active LSP session with the language server.
 *
 * Provides access to both the standard [LanguageServer] interface and the
 * typed [GroovyLanguageServerProtocol] for custom method invocation.
 *
 * ## Why both server types?
 *
 * [server] is the standard LSP interface for core protocol methods.
 * [groovyServer] provides typed access to custom @JsonRequest methods like
 * `groovy/discoverTests` and `groovy/runTest`. Without this typed interface,
 * LSP4J's [RemoteEndpoint.request] returns null for custom methods.
 *
 * @see <a href="https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol">LSP4J Extending Protocol</a>
 */
class LanguageServerSession(
    val process: Process?,
    val server: LanguageServer,
    val endpoint: RemoteEndpoint,
    val client: HarnessLanguageClient,
    private val listening: Future<Void>,
    private val stderrPump: Thread?,
    private val serverFuture: Future<*>? = null,
    private val typedServerProxy: GroovyLanguageServerProtocol? = null,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(LanguageServerSession::class.java)

    /**
     * Typed server proxy for invoking custom @JsonRequest methods.
     *
     * Returns the [GroovyLanguageServerProtocol] proxy which provides compile-time
     * type safety for custom LSP methods. Throws if typed server is not available
     * (e.g., if session was created with legacy launcher).
     *
     * @throws IllegalStateException if typed server proxy is not available
     */
    val groovyServer: GroovyLanguageServerProtocol
        get() = typedServerProxy ?: error(
            "Typed server proxy not available. " +
                "Ensure session was created with Launcher.Builder using GroovyLanguageServerProtocol.",
        )

    override fun close() {
        try {
            if (process != null && process.isAlive) {
                logger.debug("Waiting for language server process to finish")
                process.waitFor(SHUTDOWN_TIMEOUT.seconds, TimeUnit.SECONDS)
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while waiting for language server process shutdown", ex)
        } finally {
            if (process != null && process.isAlive) {
                logger.warn("Language server process still alive after timeout; terminating forcibly")
                process.destroyForcibly()
            }
        }

        listening.cancel(true)
        serverFuture?.cancel(true)

        if (stderrPump != null && stderrPump.isAlive) {
            try {
                stderrPump.join(STDERR_PUMP_JOIN_TIMEOUT.toMillis())
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.debug("Interrupted while waiting for stderr pump thread", ex)
            }
        }
    }

    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val STDERR_PUMP_JOIN_TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}
