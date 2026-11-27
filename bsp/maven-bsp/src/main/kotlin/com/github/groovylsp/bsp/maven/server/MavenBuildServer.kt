// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CleanCacheResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DebugSessionAddress
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.InverseSourcesParams
import ch.epfl.scala.bsp4j.InverseSourcesResult
import ch.epfl.scala.bsp4j.JvmRunEnvironmentParams
import ch.epfl.scala.bsp4j.JvmRunEnvironmentResult
import ch.epfl.scala.bsp4j.JvmTestEnvironmentParams
import ch.epfl.scala.bsp4j.JvmTestEnvironmentResult
import ch.epfl.scala.bsp4j.OutputPathItem
import ch.epfl.scala.bsp4j.OutputPathItemKind
import ch.epfl.scala.bsp4j.OutputPathsItem
import ch.epfl.scala.bsp4j.OutputPathsParams
import ch.epfl.scala.bsp4j.OutputPathsResult
import ch.epfl.scala.bsp4j.ResourcesItem
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.ResourcesResult
import ch.epfl.scala.bsp4j.RunParams
import ch.epfl.scala.bsp4j.RunResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import com.github.groovylsp.bsp.maven.deps.MavenDependencyProvider
import com.github.groovylsp.bsp.maven.targets.MavenBuildTargetProvider
import com.github.groovylsp.bsp.maven.targets.MavenSourceProvider
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import com.github.groovylsp.bsp.maven.workspace.MavenWorkspaceScanner
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Maven Build Server Protocol implementation.
 *
 * Provides Maven project metadata over BSP 2.1. Build execution is unavailable.
 *
 * @param scan finds the workspace's modules; tests replace the [MavenWorkspaceScanner] the public constructor uses
 */
@Suppress("TooManyFunctions") // BSP protocol implementation - one method per BSP endpoint (21 requests)
class MavenBuildServer internal constructor(
    private val workspaceRoot: Path,
    repositorySystem: RepositorySystem,
    sessionSupplier: () -> RepositorySystemSession,
    private val scan: (Path) -> List<MavenModuleInfo>,
) : BuildServer {
    private val logger = KotlinLogging.logger {}

    private val targetProvider = MavenBuildTargetProvider()
    private val sourceProvider = MavenSourceProvider()
    private val dependencyProvider = MavenDependencyProvider(repositorySystem, sessionSupplier)

    // This published module keeps java.util.concurrent rather than depending on :common:concurrent (#2474).
    private val exitListener = AtomicReference<(() -> Unit)?>(null)
    private val shutdown = AtomicBoolean(false)

    /**
     * The modules of the scan requested last, as a future that never fails. A scan swaps its future in before it
     * starts, so a request made after a reload waits for that reload's scan, and an older scan that finishes later
     * changes nothing a request can see. A scan that fails passes on the modules of the scan before it.
     */
    private val latestModules = AtomicReference<CompletableFuture<List<MavenModuleInfo>>>(
        CompletableFuture.completedFuture(emptyList()),
    )

    /** True once `build/shutdown` was received; the standalone launcher maps it to the process exit code. */
    val shutdownRequested: Boolean
        get() = shutdown.get()

    /** Creates a server that finds the modules under [workspaceRoot] with a [MavenWorkspaceScanner]. */
    constructor(
        workspaceRoot: Path,
        repositorySystem: RepositorySystem,
        sessionSupplier: () -> RepositorySystemSession,
    ) : this(workspaceRoot, repositorySystem, sessionSupplier, MavenWorkspaceScanner()::scan)

    /**
     * Accepts the client proxy. The server sends the client no notifications yet (#821), so it keeps no reference to
     * it; the method stays for the launcher and for existing callers of the published API.
     */
    @Suppress("UnusedParameter") // Preserve the published signature; notifications will need the client.
    fun connect(client: BuildClient) = Unit

    /**
     * Registers the owner's reaction to `build/exit`. The server never terminates the process itself:
     * the executable decides how and when to stop. A later call replaces the earlier listener.
     */
    fun onExit(listener: () -> Unit) {
        exitListener.set(listener)
    }

    // === BSP Lifecycle ===

    override fun buildInitialize(params: InitializeBuildParams): CompletableFuture<InitializeBuildResult> {
        logger.info { "Initializing Maven BSP server for workspace: ${params.rootUri}" }

        return scanWorkspace().thenApply { modules ->
            logger.info { "Found ${modules.size} Maven modules" }

            InitializeBuildResult(
                "Maven BSP",
                "0.1.0",
                "2.1.0",
                MavenBspCapabilities.serverCapabilities(),
            )
        }
    }

    override fun onBuildInitialized() {
        logger.info { "Maven BSP server initialized" }
    }

    override fun buildShutdown(): CompletableFuture<Any> {
        logger.info { "Shutting down Maven BSP server" }
        shutdown.set(true)
        return CompletableFuture.completedFuture(null)
    }

    override fun onBuildExit() {
        logger.info { "Maven BSP server exiting" }
        exitListener.get()?.invoke()
    }

    // === Workspace Operations ===

    override fun workspaceBuildTargets(): CompletableFuture<WorkspaceBuildTargetsResult> = withModules { modules ->
        val targets = targetProvider.createTargets(modules)
        logger.info { "Returning ${targets.size} build targets" }
        WorkspaceBuildTargetsResult(targets)
    }

    override fun workspaceReload(): CompletableFuture<Any> {
        logger.info { "Reloading workspace" }
        return scanWorkspace().thenApply { modules ->
            logger.info { "Reload complete: ${modules.size} modules" }
            null
        }
    }

    /**
     * Scans the workspace and makes the result what later requests see. The returned future fails when the scan
     * does; requests then keep the modules of the scan before it.
     */
    private fun scanWorkspace(): CompletableFuture<List<MavenModuleInfo>> {
        val published = CompletableFuture<List<MavenModuleInfo>>()
        val previous = latestModules.getAndSet(published)
        val scanned = CompletableFuture.supplyAsync { scan(workspaceRoot) }
        return scanned.whenComplete { found, failure ->
            if (failure == null) {
                published.complete(found)
            } else {
                previous.thenAccept { kept -> published.complete(kept) }
            }
        }
    }

    /** Runs [handler] on one snapshot of the modules, once the scan requested last has finished. */
    private fun <T> withModules(handler: (List<MavenModuleInfo>) -> T): CompletableFuture<T> =
        latestModules.get().thenApplyAsync { modules -> handler(modules) }

    // === Build Target Operations ===

    override fun buildTargetSources(params: SourcesParams): CompletableFuture<SourcesResult> = withModules { modules ->
        val requestedTargets = params.targets.map { it.uri }.toSet()
        val relevantModules = modules.filter { module ->
            val mainUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN).uri
            val testUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST).uri
            mainUri in requestedTargets || testUri in requestedTargets
        }
        sourceProvider.getSources(relevantModules)
    }

    override fun buildTargetDependencyModules(
        params: DependencyModulesParams,
    ): CompletableFuture<DependencyModulesResult> = withModules { modules ->
        val requestedTargets = params.targets.map { it.uri }.toSet()
        val relevantModules = modules.filter { module ->
            val mainUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN).uri
            val testUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST).uri
            mainUri in requestedTargets || testUri in requestedTargets
        }
        dependencyProvider.getDependencyModules(relevantModules)
    }

    override fun buildTargetDependencySources(
        params: DependencySourcesParams,
    ): CompletableFuture<DependencySourcesResult> = withModules { modules ->
        val requestedTargets = params.targets.map { it.uri }.toSet()
        val relevantModules = modules.filter { module ->
            val mainUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN).uri
            val testUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST).uri
            mainUri in requestedTargets || testUri in requestedTargets
        }
        dependencyProvider.getDependencySources(relevantModules)
    }

    override fun buildTargetResources(params: ResourcesParams): CompletableFuture<ResourcesResult> =
        withModules { modules ->
            val items = params.targets.map { targetId ->
                val resolved = findModuleForTarget(modules, targetId)
                val resources = if (resolved != null) {
                    val (module, isTest) = resolved
                    val baseDir = module.baseDir
                    val resourceDir = if (isTest) {
                        baseDir.resolve("src/test/resources")
                    } else {
                        baseDir.resolve("src/main/resources")
                    }
                    if (resourceDir.toFile().exists()) {
                        listOf(resourceDir.toUri().toString())
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
                ResourcesItem(targetId, resources)
            }
            ResourcesResult(items)
        }

    override fun buildTargetInverseSources(params: InverseSourcesParams): CompletableFuture<InverseSourcesResult> =
        withModules { modules ->
            // Find which target contains this source file
            val sourceUri = params.textDocument.uri
            val sourcePath = Path.of(java.net.URI.create(sourceUri))

            val containingTargets = modules.flatMap { module ->
                val baseDir = module.baseDir
                val isInMain = sourcePath.startsWith(baseDir.resolve("src/main"))
                val isInTest = sourcePath.startsWith(baseDir.resolve("src/test"))

                when {
                    isInMain -> listOf(targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN))
                    isInTest -> listOf(targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST))
                    else -> emptyList()
                }
            }

            InverseSourcesResult(containingTargets)
        }

    override fun buildTargetCompile(params: CompileParams): CompletableFuture<CompileResult> =
        unsupported("buildTarget/compile")

    override fun buildTargetTest(params: TestParams): CompletableFuture<TestResult> = unsupported("buildTarget/test")

    override fun buildTargetRun(params: RunParams): CompletableFuture<RunResult> = unsupported("buildTarget/run")

    override fun buildTargetCleanCache(params: CleanCacheParams): CompletableFuture<CleanCacheResult> =
        unsupported("buildTarget/cleanCache")

    override fun buildTargetOutputPaths(params: OutputPathsParams): CompletableFuture<OutputPathsResult> =
        withModules { modules ->
            val items = params.targets.map { targetId ->
                val resolved = findModuleForTarget(modules, targetId)
                val outputPaths = if (resolved != null) {
                    val (module, isTest) = resolved
                    val baseDir = module.baseDir
                    val outputDir = if (isTest) {
                        baseDir.resolve("target/test-classes")
                    } else {
                        baseDir.resolve("target/classes")
                    }
                    listOf(OutputPathItem(outputDir.toUri().toString(), OutputPathItemKind.DIRECTORY))
                } else {
                    emptyList()
                }
                OutputPathsItem(targetId, outputPaths)
            }
            OutputPathsResult(items)
        }

    // === Debug Operations (stubs) ===

    override fun debugSessionStart(params: DebugSessionParams): CompletableFuture<DebugSessionAddress> =
        unsupported("debugSession/start")

    @Deprecated("The server does not provide JVM run environments.")
    @Suppress("UnusedParameter") // Preserve the published JVM signature for existing callers.
    fun jvmRunEnvironment(params: JvmRunEnvironmentParams): CompletableFuture<JvmRunEnvironmentResult> =
        unsupported("buildTarget/jvmRunEnvironment")

    @Deprecated("The server does not provide JVM test environments.")
    @Suppress("UnusedParameter") // Preserve the published JVM signature for existing callers.
    fun jvmTestEnvironment(params: JvmTestEnvironmentParams): CompletableFuture<JvmTestEnvironmentResult> =
        unsupported("buildTarget/jvmTestEnvironment")

    private fun <T> unsupported(method: String): CompletableFuture<T> = CompletableFuture.failedFuture(
        ResponseErrorException(ResponseError(ResponseErrorCode.MethodNotFound, "$method is not supported", null)),
    )

    /**
     * Resolves a target to its module and whether it is the test target, by exact ID. A suffix check would mistake
     * the main target of a module whose artifactId is `test` (`maven:g:test`) for a test target.
     */
    private fun findModuleForTarget(
        modules: List<MavenModuleInfo>,
        targetId: BuildTargetIdentifier,
    ): Pair<MavenModuleInfo, Boolean>? {
        for (module in modules) {
            if (targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN).uri == targetId.uri) {
                return module to false
            }
            if (targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST).uri == targetId.uri) {
                return module to true
            }
        }
        return null
    }
}
