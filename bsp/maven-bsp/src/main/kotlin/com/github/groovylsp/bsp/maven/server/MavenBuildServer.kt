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

/**
 * Maven Build Server Protocol implementation.
 *
 * Provides Maven project metadata over BSP 2.1. Build execution is unavailable.
 */
@Suppress("TooManyFunctions") // BSP protocol implementation - one method per BSP endpoint (21 requests)
class MavenBuildServer(
    private val workspaceRoot: Path,
    private val repositorySystem: RepositorySystem,
    private val sessionSupplier: () -> RepositorySystemSession,
) : BuildServer {
    private val logger = KotlinLogging.logger {}

    private val scanner = MavenWorkspaceScanner()
    private val targetProvider = MavenBuildTargetProvider()
    private val sourceProvider = MavenSourceProvider()
    private val dependencyProvider = MavenDependencyProvider(repositorySystem, sessionSupplier)

    private var modules: List<MavenModuleInfo> = emptyList()
    private var client: BuildClient? = null

    fun connect(client: BuildClient) {
        this.client = client
    }

    // === BSP Lifecycle ===

    override fun buildInitialize(params: InitializeBuildParams): CompletableFuture<InitializeBuildResult> {
        logger.info { "Initializing Maven BSP server for workspace: ${params.rootUri}" }

        return CompletableFuture.supplyAsync {
            modules = scanner.scan(workspaceRoot)
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
        return CompletableFuture.completedFuture(null)
    }

    override fun onBuildExit() {
        // TODO(#1838): Give the standalone launcher an owned, bounded process-exit policy.
        logger.info { "Maven BSP server exiting" }
    }

    // === Workspace Operations ===

    override fun workspaceBuildTargets(): CompletableFuture<WorkspaceBuildTargetsResult> =
        CompletableFuture.supplyAsync {
            val targets = targetProvider.createTargets(modules)
            logger.info { "Returning ${targets.size} build targets" }
            WorkspaceBuildTargetsResult(targets)
        }

    override fun workspaceReload(): CompletableFuture<Any> = CompletableFuture.supplyAsync {
        logger.info { "Reloading workspace" }
        modules = scanner.scan(workspaceRoot)
        logger.info { "Reload complete: ${modules.size} modules" }
        null
    }

    // === Build Target Operations ===

    override fun buildTargetSources(params: SourcesParams): CompletableFuture<SourcesResult> =
        CompletableFuture.supplyAsync {
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
    ): CompletableFuture<DependencyModulesResult> = CompletableFuture.supplyAsync {
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
    ): CompletableFuture<DependencySourcesResult> = CompletableFuture.supplyAsync {
        val requestedTargets = params.targets.map { it.uri }.toSet()
        val relevantModules = modules.filter { module ->
            val mainUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN).uri
            val testUri = targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.TEST).uri
            mainUri in requestedTargets || testUri in requestedTargets
        }
        dependencyProvider.getDependencySources(relevantModules)
    }

    override fun buildTargetResources(params: ResourcesParams): CompletableFuture<ResourcesResult> =
        CompletableFuture.supplyAsync {
            val items = params.targets.map { targetId ->
                val module = findModuleForTarget(targetId)
                val resources = if (module != null) {
                    val baseDir = module.baseDir
                    val isTest = targetId.uri.endsWith(":test")
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
        CompletableFuture.supplyAsync {
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
        CompletableFuture.supplyAsync {
            val items = params.targets.map { targetId ->
                val module = findModuleForTarget(targetId)
                val outputPaths = if (module != null) {
                    val baseDir = module.baseDir
                    val isTest = targetId.uri.endsWith(":test")
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

    private fun findModuleForTarget(targetId: BuildTargetIdentifier): MavenModuleInfo? {
        val uri = targetId.uri.removeSuffix(":test")
        return modules.find { module ->
            targetProvider.buildTargetId(module, MavenBuildTargetProvider.Scope.MAIN).uri == uri
        }
    }
}
