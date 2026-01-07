package com.github.groovylsp.bsp.lifecycle

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CleanCacheResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.ResourcesResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Wrapper around BuildServer that provides:
 * - Coroutine-based async operations (using suspend functions)
 * - Either-based error handling instead of exceptions
 * - Proper resource management
 *
 * This abstraction layer sits between the BSP4J transport and our domain logic.
 */
class BuildServerConnection(private val server: BuildServer, private val serverProcess: Process) : Closeable {
    private val logger = LoggerFactory.getLogger(BuildServerConnection::class.java)

    @Volatile
    private var initialized = false

    /**
     * Initialize the build server connection.
     */
    suspend fun initialize(params: InitializeBuildParams): Either<BspError, InitializeBuildResult> = runCatchingBsp {
        val result = server.buildInitialize(params).await()
        server.onBuildInitialized()
        initialized = true
        logger.info("BSP server initialized: ${result.displayName} v${result.version}")
        result.right()
    }

    /**
     * Get all build targets in the workspace.
     */
    suspend fun workspaceBuildTargets(): Either<BspError, WorkspaceBuildTargetsResult> = runCatchingBsp {
        requireInitialized()
        server.workspaceBuildTargets().await().right()
    }

    /**
     * Get source files for the specified build targets.
     */
    suspend fun buildTargetSources(targetIds: List<BuildTargetIdentifier>): Either<BspError, SourcesResult> =
        runCatchingBsp {
            requireInitialized()
            if (targetIds.isEmpty()) return SourcesResult(emptyList()).right()
            server.buildTargetSources(SourcesParams(targetIds)).await().right()
        }

    /**
     * Get dependency modules for the specified build targets.
     */
    suspend fun buildTargetDependencyModules(
        targetIds: List<BuildTargetIdentifier>,
    ): Either<BspError, DependencyModulesResult> = runCatchingBsp {
        requireInitialized()
        if (targetIds.isEmpty()) return DependencyModulesResult(emptyList()).right()
        server.buildTargetDependencyModules(DependencyModulesParams(targetIds)).await().right()
    }

    /**
     * Get resources for the specified build targets.
     */
    suspend fun buildTargetResources(targetIds: List<BuildTargetIdentifier>): Either<BspError, ResourcesResult> =
        runCatchingBsp {
            requireInitialized()
            if (targetIds.isEmpty()) return ResourcesResult(emptyList()).right()
            server.buildTargetResources(ResourcesParams(targetIds)).await().right()
        }

    /**
     * Compile the specified build targets.
     */
    suspend fun compile(targetIds: List<BuildTargetIdentifier>): Either<BspError, CompileResult> = runCatchingBsp {
        requireInitialized()
        if (targetIds.isEmpty()) return BspError.InvalidRequest("No targets to compile").left()
        logger.info("Compiling ${targetIds.size} targets")
        server.buildTargetCompile(CompileParams(targetIds)).await().right()
    }

    /**
     * Test the specified build targets.
     */
    suspend fun test(targetIds: List<BuildTargetIdentifier>): Either<BspError, TestResult> = runCatchingBsp {
        requireInitialized()
        if (targetIds.isEmpty()) return BspError.InvalidRequest("No targets to test").left()
        logger.info("Testing ${targetIds.size} targets")
        server.buildTargetTest(TestParams(targetIds)).await().right()
    }

    /**
     * Clean the cache for the specified build targets.
     */
    suspend fun cleanCache(targetIds: List<BuildTargetIdentifier>): Either<BspError, CleanCacheResult> =
        runCatchingBsp {
            requireInitialized()
            if (targetIds.isEmpty()) return CleanCacheResult(true).right()
            logger.info("Cleaning cache for ${targetIds.size} targets")
            server.buildTargetCleanCache(CleanCacheParams(targetIds)).await().right()
        }

    /**
     * Shutdown the build server gracefully.
     */
    suspend fun shutdown(): Either<BspError, Unit> = runCatchingBsp {
        if (!initialized) return Unit.right()
        logger.info("Shutting down BSP server")
        server.buildShutdown().await()
        server.onBuildExit()
        initialized = false
        Unit.right()
    }

    /**
     * Check if the server process is alive.
     */
    fun isAlive(): Boolean = serverProcess.isAlive

    override fun close() {
        try {
            // NOTE: Using runBlocking here is acceptable for cleanup code
            //   Alternatives would be more complex (CompletableFuture.get with timeout)
            kotlinx.coroutines.runBlocking {
                shutdown()
            }
        } catch (e: Exception) {
            logger.warn("Error during shutdown: ${e.message}")
        } finally {
            if (serverProcess.isAlive) {
                logger.warn("Forcibly terminating BSP server process")
                serverProcess.destroyForcibly()
            }
        }
    }

    private fun requireInitialized() {
        check(initialized) { "BuildServer not initialized. Call initialize() first." }
    }

    private suspend inline fun <T> runCatchingBsp(block: suspend () -> Either<BspError, T>): Either<BspError, T> = try {
        block()
    } catch (e: Exception) {
        logger.error("BSP operation failed: ${e.message}", e)
        BspError.RequestFailed(e.message ?: "Unknown error", e).left()
    }

    /**
     * Errors that can occur during BSP operations.
     */
    sealed class BspError(val message: String, val cause: Throwable? = null) {
        data class RequestFailed(val reason: String, val exception: Throwable? = null) :
            BspError(reason, exception)

        data class InvalidRequest(val reason: String) : BspError(reason)

        override fun toString(): String = "BspError: $message"
    }
}
