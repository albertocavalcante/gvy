package com.github.albertocavalcante.groovylsp.e2e

import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import com.github.albertocavalcante.groovylsp.testing.client.HarnessLanguageClient
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.launch.LSPLauncher
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
        val launcher = LSPLauncher.createClientLauncher(
            client,
            process.inputStream,
            process.outputStream,
        )
        val listening = launcher.startListening()

        val stderrPump = startErrorPump(process, scenarioName)

        return LanguageServerSession(
            process = process,
            server = launcher.remoteProxy,
            endpoint = launcher.remoteEndpoint,
            client = client,
            listening = listening,
            stderrPump = stderrPump,
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
        val serverExecutor = inProcessExecutor

        // Launch server listener in a separate thread
        val listening = serverExecutor.submit {
            try {
                val launcher = LSPLauncher.createServerLauncher(
                    server,
                    serverIn,
                    serverOut,
                )
                // Use the launcher's executor service to run the loop
                launcher.startListening().get()
            } catch (e: Exception) {
                logger.error("In-process server error for scenario '{}'", scenarioName, e)
            }
        }

        val client = HarnessLanguageClient()
        val clientLauncher = LSPLauncher.createClientLauncher(
            client,
            clientIn,
            clientOut,
        )
        val clientListening = clientLauncher.startListening()

        logger.info("Started in-process language server for scenario '{}'", scenarioName)

        return LanguageServerSession(
            process = null,
            server = clientLauncher.remoteProxy,
            endpoint = clientLauncher.remoteEndpoint,
            client = client,
            listening = clientListening, // We track client's listener; server's is managed by executor
            stderrPump = null,
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

class LanguageServerSession(
    private val process: Process?,
    val server: LanguageServer,
    val endpoint: RemoteEndpoint,
    val client: HarnessLanguageClient,
    private val listening: Future<Void>,
    private val stderrPump: Thread?,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(LanguageServerSession::class.java)

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
