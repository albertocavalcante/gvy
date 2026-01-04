package com.github.albertocavalcante.groovylsp.buildtool.bsp

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val BSP_VERSION = "2.1.0"
private const val CLIENT_NAME = "groovy-lsp"
private const val CLIENT_VERSION = "1.0.0"
private const val INIT_TIMEOUT_SEC = 60L
private const val REQUEST_TIMEOUT_SEC = 120L
private const val SHUTDOWN_TIMEOUT_SEC = 10L

/**
 * BSP client for communicating with build servers (bazel-bsp, sbt, Mill).
 */
class BspClient(private val connection: BspConnectionDetails, private val workspaceRoot: Path) : Closeable {

    private val logger = LoggerFactory.getLogger(BspClient::class.java)
    private var process: Process? = null
    private var server: BuildServer? = null
    private var initialized = false

    fun connect(onProgress: ((String) -> Unit)? = null): Boolean {
        onProgress?.invoke("Starting ${connection.name}...")
        return runCatching {
            startProcess()
            initialize(onProgress)
            true
        }.onFailure { e ->
            logger.error("Failed to connect to BSP server: ${e.message}", e)
            close()
        }.getOrDefault(false)
    }

    fun workspaceBuildTargets(): WorkspaceBuildTargetsResult? = runCatching {
        requireInitialized()
        server?.workspaceBuildTargets()?.get(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
    }.onFailure {
        logger.error("Failed to get build targets: ${it.message}", it)
    }.getOrNull()

    fun buildTargetSources(targetIds: List<BuildTargetIdentifier>): SourcesResult? {
        if (targetIds.isEmpty()) return SourcesResult(emptyList())
        return runCatching {
            requireInitialized()
            server?.buildTargetSources(SourcesParams(targetIds))?.get(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
        }.onFailure {
            logger.error("Failed to get sources: ${it.message}", it)
        }.getOrNull()
    }

    fun buildTargetDependencyModules(targetIds: List<BuildTargetIdentifier>): DependencyModulesResult? {
        if (targetIds.isEmpty()) return DependencyModulesResult(emptyList())
        return runCatching {
            requireInitialized()
            server?.buildTargetDependencyModules(DependencyModulesParams(targetIds))
                ?.get(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
        }.onFailure {
            logger.error("Failed to get dependencies: ${it.message}", it)
        }.getOrNull()
    }

    override fun close() {
        runCatching {
            if (initialized) {
                server?.buildShutdown()?.get(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)
                server?.onBuildExit()
            }
        }.onFailure { logger.warn("Error during BSP shutdown: ${it.message}") }
        process?.destroyForcibly()
        process = null
        server = null
        initialized = false
    }

    private fun startProcess() {
        logger.info("Starting BSP server: ${connection.argv.joinToString(" ")}")

        val newProcess = ProcessBuilder(connection.argv)
            .directory(workspaceRoot.toFile())
            .start()
        this.process = newProcess

        // Asynchronously log stderr from the BSP server for better diagnostics
        CompletableFuture.runAsync {
            newProcess.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { logger.warn("[BSP stderr] {}", it) }
            }
        }

        val launcher = Launcher.Builder<BuildServer>()
            .setRemoteInterface(BuildServer::class.java)
            .setInput(newProcess.inputStream)
            .setOutput(newProcess.outputStream)
            .setLocalService(ClientHandler())
            .create()

        server = launcher.remoteProxy
        CompletableFuture.runAsync { launcher.startListening() }
        logger.info("BSP server process started")
    }

    private fun initialize(onProgress: ((String) -> Unit)?) {
        onProgress?.invoke("Initializing ${connection.name}...")

        val params = InitializeBuildParams(
            CLIENT_NAME,
            CLIENT_VERSION,
            BSP_VERSION,
            workspaceRoot.toUri().toString(),
            BuildClientCapabilities(listOf("groovy", "java")),
        )

        val result = server?.buildInitialize(params)?.get(INIT_TIMEOUT_SEC, TimeUnit.SECONDS)
            ?: error("BSP server returned null for initialize")

        logger.info("BSP initialized: ${result.displayName ?: connection.name} v${result.version ?: "?"}")
        server?.onBuildInitialized()
        initialized = true
        onProgress?.invoke("${connection.name} ready")
    }

    private fun requireInitialized() {
        check(initialized) { "BSP client not initialized. Call connect() first." }
    }

    private inner class ClientHandler : BuildClient {
        override fun onBuildShowMessage(params: ShowMessageParams) {
            logger.info("[BSP] ${params.message}")
        }

        override fun onBuildLogMessage(params: LogMessageParams) {
            logger.debug("[BSP] ${params.message}")
        }

        override fun onBuildPublishDiagnostics(params: PublishDiagnosticsParams) = Unit

        override fun onBuildTargetDidChange(params: DidChangeBuildTarget) {
            logger.info("[BSP] Build targets changed")
        }

        override fun onBuildTaskStart(params: TaskStartParams) {
            logger.debug("[BSP] Task started: ${params.message ?: params.taskId.id}")
        }

        override fun onBuildTaskProgress(params: TaskProgressParams) {
            logger.debug("[BSP] Progress: ${params.message ?: ""}")
        }

        override fun onBuildTaskFinish(params: TaskFinishParams) {
            logger.debug("[BSP] Task finished: ${params.message ?: params.taskId.id}")
        }
    }
}
