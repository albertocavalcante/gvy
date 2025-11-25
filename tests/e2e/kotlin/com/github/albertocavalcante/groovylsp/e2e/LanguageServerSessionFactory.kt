package com.github.albertocavalcante.groovylsp.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.albertocavalcante.groovylsp.testing.client.HarnessLanguageClient
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class LanguageServerSessionFactory(private val mapper: ObjectMapper) {
    private val logger = LoggerFactory.getLogger(LanguageServerSessionFactory::class.java)

    private val execJar: Path? = System.getProperty("groovy.lsp.e2e.execJar")
        ?.let { Path.of(it) }
        ?.takeIf { Files.exists(it) }

    private val serverClasspath: String? = System.getProperty("groovy.lsp.e2e.serverClasspath")
    private val mainClass: String? = System.getProperty("groovy.lsp.e2e.mainClass")
    private val gradleUserHome: Path? = resolveGradleUserHome()

    fun start(serverConfig: ServerConfig, scenarioName: String): LanguageServerSession {
        val launchMode = serverConfig.mode
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
        // Keep Gradle model resolution from stalling e2e runs; the server reads this for its timeout.
        processBuilder.environment().putIfAbsent(
            "GROOVY_LSP_GRADLE_TIMEOUT_MS",
            System.getProperty("groovy.lsp.e2e.gradleTimeoutMs") ?: "15000",
        )

        val process = processBuilder.start()

        val client = HarnessLanguageClient(mapper)
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
        }
    }

    private fun resolveGradleUserHome(): Path? {
        val override = System.getProperty("groovy.lsp.e2e.gradleUserHome")
            ?: System.getenv("GROOVY_LSP_E2E_GRADLE_USER_HOME")

        val target = when {
            !override.isNullOrBlank() -> Paths.get(override)
            else -> Paths.get("").toAbsolutePath().resolve("build/e2e-gradle-home")
        }

        return runCatching {
            Files.createDirectories(target)
            target
        }.onFailure {
            logger.warn("Failed to prepare isolated Gradle user home at {}: {}", target, it.message)
        }.getOrNull()
    }

    private fun startErrorPump(process: Process, scenarioName: String): Thread {
        val logFile = resolveGradleUserHome()?.parent?.resolve("e2e-logs")?.resolve("$scenarioName.log")
            ?: Paths.get("build/e2e-logs/$scenarioName.log")

        Files.createDirectories(logFile.parent)

        val thread = Thread(
            {
                try {
                    Files.newBufferedWriter(logFile).use { writer ->
                        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                // Write to file
                                writer.write(line)
                                writer.newLine()
                                writer.flush()

                                // Also log to test runner logger for visibility (optional, maybe reduce level)
                                logger.info("[server:{}] {}", scenarioName, line)

                                line = reader.readLine()
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error pumping stderr to file $logFile", e)
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
    private val process: Process,
    val server: LanguageServer,
    val endpoint: RemoteEndpoint,
    val client: HarnessLanguageClient,
    private val listening: Future<Void>,
    private val stderrPump: Thread,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(LanguageServerSession::class.java)

    override fun close() {
        try {
            if (process.isAlive) {
                logger.debug("Waiting for language server process to finish")
                process.waitFor(SHUTDOWN_TIMEOUT.seconds, TimeUnit.SECONDS)
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while waiting for language server process shutdown", ex)
        } finally {
            if (process.isAlive) {
                logger.warn("Language server process still alive after timeout; terminating forcibly")
                process.destroyForcibly()
            }
        }

        listening.cancel(true)

        if (stderrPump.isAlive) {
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
