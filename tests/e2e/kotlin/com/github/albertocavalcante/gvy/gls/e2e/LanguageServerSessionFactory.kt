package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.gvy.gls.testing.api.GroovyLanguageServerProtocol
import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import com.github.albertocavalcante.gvy.gls.transport.lsp4j.registerStatusNotificationAdapter
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubsLocation
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Test-JVM system properties forwarded to a language-server subprocess. */
private val FORWARDED_PROPERTIES = listOf(JenkinsStubsLocation.CACHE_ROOT_PROPERTY)

class LanguageServerSessionFactory(
    private val startProcess: (ProcessBuilder) -> Process = ProcessBuilder::start,
    private val createServer: ((Int) -> Unit) -> LanguageServer = { onExit ->
        GroovyLanguageServer(exitProcess = onExit)
    },
) {
    private val logger = KotlinLogging.logger {}

    private val execJar: Path? = System.getProperty("groovy.lsp.e2e.execJar")
        ?.let { BazelRunfiles.resolve(it) }

    private val serverClasspath: String? = System.getProperty("groovy.lsp.e2e.serverClasspath")
    private val mainClass: String? = System.getProperty("groovy.lsp.e2e.mainClass")
    private val gradleUserHome: Path by lazy { resolveGradleUserHome() }

    fun start(serverConfig: ServerConfig, scenarioName: String): LanguageServerSession {
        val launchMode = serverConfig.mode

        return if (launchMode == ServerLaunchMode.InProcess) {
            require(serverConfig.args.isEmpty()) { "In-process mode cannot apply server args" }
            require(serverConfig.env.isEmpty()) { "In-process mode cannot apply server env overrides" }
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

        val command = buildCommand(serverConfig)
        logger.info {
            "Starting language server for scenario '$scenarioName' using command: ${command.joinToString(" ")}"
        }
        val processBuilder = ProcessBuilder(command).redirectErrorStream(false)
        if ("GRADLE_USER_HOME" !in serverConfig.env) {
            processBuilder.environment()["GRADLE_USER_HOME"] = gradleUserHome.toAbsolutePath().toString()
        }
        // Explicit authored environment wins over the isolated default, including GRADLE_USER_HOME.
        processBuilder.environment().putAll(serverConfig.env)

        return SessionResources().use { pending ->
            val executor = pending.own(ListenerExecutor(scenarioName))
            val ownedProcess = pending.own(ServerProcess(startProcess(processBuilder), pending.interruption))
            val process = ownedProcess.process
            ownedProcess.stderrPump = startErrorPump(process, scenarioName)
            val client = HarnessLanguageClient()
            val launcher = Launcher.Builder<GroovyLanguageServerProtocol>()
                .setLocalService(client)
                .setRemoteInterface(GroovyLanguageServerProtocol::class.java)
                .setInput(process.inputStream)
                .setOutput(process.outputStream)
                .setExecutorService(executor.service)
                .configureGson {
                    it.registerRawJsonRpcParams().preserveUntypedJsonNumbers()
                    registerStatusNotificationAdapter(it)
                }
                .create()
            val listening = launcher.startListening()
            pending.own(AutoCloseable { listening.cancel(true) })

            val proxy = launcher.remoteProxy
            LanguageServerSession(
                process = process,
                server = proxy,
                endpoint = launcher.remoteEndpoint,
                client = client,
                typedServerProxy = proxy,
                resources = pending.transfer(),
            )
        }
    }

    private fun buildCommand(config: ServerConfig): List<String> {
        val jvm = listOf(resolveJavaBinary(), "-Xmx1g") + forwardedSystemProperties()
        val launchArgs = when {
            serverClasspath != null && mainClass != null -> jvm + listOf("-cp", serverClasspath, mainClass)
            execJar != null -> jvm + listOf("-jar", execJar.toString())
            else -> error(
                "Unable to locate language server executable; expected either groovy.lsp.e2e.execJar or " +
                    "(groovy.lsp.e2e.serverClasspath & groovy.lsp.e2e.mainClass) system properties",
            )
        }
        require(config.mode == ServerLaunchMode.Stdio) { "Only stdio can launch a subprocess" }
        return launchArgs + "stdio" + config.args
    }

    /** Test-JVM properties the server subprocess must inherit to stay isolated. */
    private fun forwardedSystemProperties(): List<String> = FORWARDED_PROPERTIES.mapNotNull { key ->
        System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { "-D$key=$it" }
    }

    private fun resolveGradleUserHome(): Path {
        val override = sequenceOf(
            System.getProperty("groovy.lsp.e2e.gradleUserHome"),
            System.getenv("GROOVY_LSP_E2E_GRADLE_USER_HOME"),
        ).firstOrNull { !it.isNullOrBlank() }

        // Use unique suffix per JVM to support parallel test execution.
        // Multiple test forks can safely share the parent directory (Gradle handles locking),
        // but isolating prevents any potential race conditions during heavy parallel load.
        val uniqueSuffix = ProcessHandle.current().pid()
        val target = when {
            !override.isNullOrBlank() -> Paths.get(override)
            else -> defaultIsolatedGradleHome(System.getenv(), Paths.get("").toAbsolutePath(), uniqueSuffix)
        }

        return checkNotNull(GradleWrapperDistributions.prepareIsolatedHome(target, ::shareFixtureDistributions)) {
            "Cannot prepare isolated Gradle user home at $target"
        }
    }

    /** Reuses the host's installed fixture Gradle distributions instead of downloading them per JVM. */
    private fun shareFixtureDistributions(gradleUserHome: Path) {
        val scenarioDir = System.getProperty("groovy.lsp.e2e.scenarioDir")
            ?: System.getProperty("groovy.lsp.e2e.scenarioDir.marker")?.let { Paths.get(it).parent?.toString() }
        if (scenarioDir == null) {
            logger.info { "Scenario directory unknown; fixture Gradle distributions will be downloaded" }
            return
        }
        GradleWrapperDistributions.share(
            GradleWrapperDistributions.hostGradleUserHome(),
            gradleUserHome,
            GradleWrapperDistributions.fixtureDistributions(Paths.get(scenarioDir).resolveSibling("workspaces")),
        )
    }

    private fun createInProcessSession(scenarioName: String): LanguageServerSession =
        SessionResources().use { pending ->
            val executor = pending.own(ListenerExecutor(scenarioName))
            val clientIn = pending.own(PipedInputStream())
            val serverOut = pending.own(PipedOutputStream(clientIn))
            val serverIn = pending.own(PipedInputStream())
            val clientOut = pending.own(PipedOutputStream(serverIn))
            val exitCode = CompletableFuture<Int>()
            val server = createServer { exitCode.complete(it) }
            pending.own(AutoCloseable { server.shutdown().get(SERVER_SHUTDOWN_TIMEOUT.seconds, TimeUnit.SECONDS) })
            require(server is LanguageClientAware) { "In-process server must accept a language client" }
            val serverLauncher = Launcher.Builder<GroovyLanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(GroovyLanguageClient::class.java)
                .setInput(serverIn)
                .setOutput(serverOut)
                .setExecutorService(executor.service)
                .configureGson(::registerStatusNotificationAdapter)
                .create()
            server.connect(serverLauncher.remoteProxy)
            val serverListening = serverLauncher.startListening()
            pending.own(AutoCloseable { serverListening.cancel(true) })

            val client = HarnessLanguageClient()
            val clientLauncher = Launcher.Builder<GroovyLanguageServerProtocol>()
                .setLocalService(client)
                .setRemoteInterface(GroovyLanguageServerProtocol::class.java)
                .setInput(clientIn)
                .setOutput(clientOut)
                .setExecutorService(executor.service)
                .configureGson {
                    it.registerRawJsonRpcParams().preserveUntypedJsonNumbers()
                    registerStatusNotificationAdapter(it)
                }
                .create()
            val clientListening = clientLauncher.startListening()
            pending.own(AutoCloseable { clientListening.cancel(true) })
            logger.info { "Started in-process language server for scenario '$scenarioName'" }

            val proxy = clientLauncher.remoteProxy
            LanguageServerSession(
                process = null,
                server = proxy,
                endpoint = clientLauncher.remoteEndpoint,
                client = client,
                exitCode = exitCode,
                typedServerProxy = proxy,
                resources = pending.transfer(),
            )
        }

    private fun startErrorPump(process: Process, scenarioName: String): Thread {
        val thread = Thread(
            {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        logger.info { "[server:$scenarioName] $line" }
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

    private companion object {
        val SERVER_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}

/** Terminates an owned subprocess before closing streams that may have blocked readers. */
private class ServerProcess(val process: Process, private val interruption: CleanupInterruption) : AutoCloseable {
    var stderrPump: Thread? = null

    override fun close() {
        AutoCloseable { closeStreams() }.use {
            if (process.isAlive) process.destroyForcibly()
            check(interruption.observe { process.waitFor(CLOSE_TIMEOUT.seconds, TimeUnit.SECONDS) }) {
                "LSP process did not stop"
            }
        }
    }

    private fun closeStreams() {
        process.inputStream.use {
            process.outputStream.use {
                process.errorStream.use {
                    interruption.observe { stderrPump?.join(CLOSE_TIMEOUT.toMillis()) }
                    check(stderrPump?.isAlive != true) { "LSP stderr reader did not stop" }
                }
            }
        }
    }
}

private const val CLOSE_TIMEOUT_SECONDS = 5L
private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(CLOSE_TIMEOUT_SECONDS)

internal fun defaultIsolatedGradleHome(
    environment: Map<String, String>,
    workingDirectory: Path,
    processId: Long,
): Path {
    val parent = environment["TEST_TMPDIR"]?.takeIf(String::isNotBlank)?.let(Paths::get)
        ?: workingDirectory.resolve("build")
    return parent.resolve("e2e-gradle-home-$processId")
}
