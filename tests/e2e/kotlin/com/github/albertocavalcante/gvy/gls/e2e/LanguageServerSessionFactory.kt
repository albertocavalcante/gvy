// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test-JVM system properties forwarded to a language-server subprocess. */
private val FORWARDED_PROPERTIES = listOf(JenkinsStubsLocation.CACHE_ROOT_PROPERTY)

/**
 * Creates sessions through the existing process and protocol ownership boundary.
 *
 * Packaged launch verifies artifact identity; callers must select [SessionEvidencePolicy.Private] to protect
 * factory-owned launch, stderr and failure output. Default retains normal diagnostic output. Private mode supports
 * stdio only and requires an explicitly authored Gradle user home.
 * Client/protocol logging needs its own privacy boundary.
 */
class LanguageServerSessionFactory(
    private val startProcess: (ProcessBuilder) -> Process = ProcessBuilder::start,
    private val createServer: ((Int) -> Unit) -> LanguageServer = { onExit ->
        GroovyLanguageServer(exitProcess = onExit)
    },
    private val packagedLaunch: PackagedLaunch? = null,
    private val evidencePolicy: SessionEvidencePolicy = SessionEvidencePolicy.Default,
) {
    private val logger = KotlinLogging.logger {}

    private val configuredExecJar: String? = System.getProperty("groovy.lsp.e2e.execJar")
    private val execJar: Path? by lazy { configuredExecJar?.let { BazelRunfiles.resolve(it) } }

    private val serverClasspath: String? = System.getProperty("groovy.lsp.e2e.serverClasspath")
    private val mainClass: String? = System.getProperty("groovy.lsp.e2e.mainClass")
    private val gradleUserHome: Path by lazy { resolveGradleUserHome() }

    fun start(serverConfig: ServerConfig, scenarioName: String): LanguageServerSession {
        val launchMode = serverConfig.mode
        if (evidencePolicy == SessionEvidencePolicy.Private &&
            (launchMode != ServerLaunchMode.Stdio || serverConfig.env["GRADLE_USER_HOME"].isNullOrBlank())
        ) {
            throw PackagedLaunchException(PackagedLaunchFailure.InvalidConfiguration, LaunchVerification())
        }

        return if (launchMode == ServerLaunchMode.InProcess) {
            require(packagedLaunch == null) { "In-process mode cannot apply packaged launch" }
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

        val privateMode = evidencePolicy == SessionEvidencePolicy.Private
        val protectedLaunch = privateMode || packagedLaunch != null
        var partialVerification = LaunchVerification()
        try {
            return SessionResources().use { pending ->
                // Own the staged artifact first, so it outlives the process and all listeners.
                val prepared = packagedLaunch?.let { pending.own(it.prepare()) }
                if (prepared != null) partialVerification = prepared.verification
                val command = if (prepared == null) {
                    buildCommand(serverConfig)
                } else {
                    val settings = checkNotNull(packagedLaunch).jvmSettings
                    listOf(packagedLaunch.javaExecutable.toString()) +
                        listOfNotNull(settings.initialHeapMiB?.let { "-Xms${it}m" }, "-Xmx${settings.maxHeapMiB}m") +
                        forwardedSystemProperties() +
                        listOf("-jar", prepared.jar.toString(), "stdio") + serverConfig.args
                }
                if (!privateMode) {
                    logger.info {
                        "Starting language server for scenario '$scenarioName' using command: ${command.joinToString(
                            " ",
                        )}"
                    }
                }
                val processBuilder = ProcessBuilder(command).redirectErrorStream(false)
                if ("GRADLE_USER_HOME" !in serverConfig.env) {
                    processBuilder.environment()["GRADLE_USER_HOME"] = gradleUserHome.toAbsolutePath().toString()
                }
                processBuilder.environment().putAll(serverConfig.env)
                val before = prepared?.let {
                    val environment = processBuilder.environment()
                    val matchingKeys = environment.keys.filter { key ->
                        JAVA_OPTION_VARIABLES.any { name -> name.equals(key, ignoreCase = true) }
                    }
                    if (packagedLaunch.javaEnvironmentPolicy == JavaEnvironmentPolicy.Reject &&
                        matchingKeys.any { key -> !environment[key].isNullOrEmpty() }
                    ) {
                        throw PackagedLaunchException(
                            PackagedLaunchFailure.EnvironmentRejected,
                            it.verification.copy(javaEnvironment = VerificationState.Rejected),
                        )
                    }
                    matchingKeys.forEach(environment::remove)
                    it.verification.copy(javaEnvironment = VerificationState.Verified)
                }
                if (before != null) partialVerification = before
                val observer = if (privateMode) pending.own(PrivateStderrObserver(pending.interruption)) else null
                val executor = pending.own(ListenerExecutor(if (privateMode) "private" else scenarioName))
                val ownedProcess = pending.own(ServerProcess(startProcess(processBuilder), pending.interruption))
                val process = ownedProcess.process
                val after = prepared?.verifyAfterStart()?.copy(javaEnvironment = VerificationState.Verified)
                if (after != null) partialVerification = after
                ownedProcess.stderrPump = if (observer == null) {
                    startErrorPump(process, scenarioName)
                } else {
                    observer.start(process)
                }
                val client = HarnessLanguageClient()
                val wireCapture = BoundedWireCapture()
                val launcher = Launcher.Builder<GroovyLanguageServerProtocol>()
                    .setLocalService(client)
                    .setRemoteInterface(GroovyLanguageServerProtocol::class.java)
                    .setInput(wireCapture.input(process.inputStream))
                    .setOutput(wireCapture.output(process.outputStream))
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
                    wireCapture = wireCapture,
                    runtimeEvidence = if (before != null && after != null) {
                        RuntimeEvidence(before, after, checkNotNull(packagedLaunch).jvmSettings)
                    } else {
                        null
                    },
                    stderrEvidence = observer?.let { { it.snapshot() } },
                    privateCleanup = protectedLaunch,
                    resources = pending.transfer(),
                )
            }
        } catch (failure: Throwable) {
            if (!protectedLaunch) throw failure
            rethrowLocalControl(failure)
            val safe = if (failure is PackagedLaunchException) {
                PackagedLaunchException(failure.reason, failure.verification)
            } else {
                PackagedLaunchException(PackagedLaunchFailure.ProcessStartFailed, partialVerification)
            }
            if (failure.suppressed.isNotEmpty()) safe.addSuppressed(IllegalStateException("Session cleanup failed"))
            throw safe
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
            val wireCapture = BoundedWireCapture()
            val clientLauncher = Launcher.Builder<GroovyLanguageServerProtocol>()
                .setLocalService(client)
                .setRemoteInterface(GroovyLanguageServerProtocol::class.java)
                .setInput(wireCapture.input(clientIn))
                .setOutput(wireCapture.output(clientOut))
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
                wireCapture = wireCapture,
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

private val JAVA_OPTION_VARIABLES = listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")

private class PrivateStderrObserver(private val interruption: CleanupInterruption) : AutoCloseable {
    private val evidence = AtomicReference(StderrEvidence(0, false, false))
    private val readerFailure = AtomicReference<Throwable?>()
    private val incomplete = AtomicBoolean()
    private var reader: Thread? = null

    fun snapshot(): StderrEvidence {
        val observed = evidence.get()
        return if (readerFailure.get() == null && !incomplete.get()) observed else observed.copy(failed = true)
    }

    fun start(process: Process): Thread = Thread({
        try {
            process.errorStream.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) {
                        evidence.updateAndGet { it.copy(eof = true) }
                        break
                    }
                    evidence.updateAndGet { it.copy(bytesObserved = Math.addExact(it.bytesObserved, count.toLong())) }
                }
            }
        } catch (failure: Throwable) {
            // Retain the signal privately without formatting, logging or allocating a replacement exception.
            readerFailure.set(failure)
        }
    }, "groovy-lsp-e2e-private-stderr").also {
        reader = it
        it.isDaemon = true
        it.start()
    }

    override fun close() {
        val stillRunning = reader?.isAlive == true
        if (stillRunning) incomplete.set(true)
        val failure = readerFailure.get()
        if (failure != null) rethrowLocalControl(failure, interruption::record)
        check(!stillRunning) { "Private stderr reader did not stop" }
    }
}
