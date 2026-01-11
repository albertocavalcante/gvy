package com.github.groovylsp.bsp.client

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CleanCacheResult
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.InverseSourcesParams
import ch.epfl.scala.bsp4j.InverseSourcesResult
import ch.epfl.scala.bsp4j.OutputPathsParams
import ch.epfl.scala.bsp4j.OutputPathsResult
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.ResourcesResult
import ch.epfl.scala.bsp4j.RunParams
import ch.epfl.scala.bsp4j.RunResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.io.Closeable

private const val COMPILE_TIMEOUT_MS = 300_000L // 5 minutes
private const val TEST_TIMEOUT_MS = 300_000L // 5 minutes

/**
 * Type-safe wrapper around BSP [BuildServer] with coroutine support and capability checking.
 *
 * This class provides:
 * - Suspend functions for all BSP operations (backed by CompletableFuture→coroutine conversion)
 * - Timeout handling for all requests
 * - Capability checking with graceful degradation via [withCapability]
 * - Arrow [Either] for error handling (Left = error, Right = success)
 * - Proper resource cleanup via [Closeable]
 *
 * Based on the Metals BSP connection pattern for robust server interaction.
 *
 * @param server The underlying BSP4J server proxy
 * @param capabilities Parsed server capabilities from initialization
 * @param config Connection configuration (timeouts, etc.)
 */
class BuildServerConnection(
    private val server: BuildServer,
    private val capabilities: BspCapabilities,
    private val config: ConnectionConfig = ConnectionConfig(),
) : Closeable {

    private val logger = KotlinLogging.logger {}
    private var closed = false

    /**
     * Execute an action only if the server supports a capability.
     * Falls back to a default value if the capability is not supported.
     *
     * This enables graceful degradation when servers don't implement optional BSP features.
     *
     * Example:
     * ```kotlin
     * val sources = connection.withCapability(
     *     supported = { capabilities.supportsDependencySources() },
     *     action = { connection.buildTargetDependencySources(params) },
     *     fallback = { DependencySourcesResult(emptyList()).right() }
     * )
     * ```
     */
    suspend fun <T> withCapability(supported: () -> Boolean, action: suspend () -> T, fallback: () -> T): T =
        if (supported()) {
            action()
        } else {
            logger.debug { "Capability not supported, using fallback" }
            fallback()
        }

    /**
     * Get all build targets in the workspace.
     *
     * This is a required BSP operation and should always succeed if the server is initialized.
     */
    suspend fun workspaceBuildTargets(): Either<BspError, WorkspaceBuildTargetsResult> =
        withErrorHandling("workspaceBuildTargets") {
            withTimeout(config.requestTimeout.inWholeMilliseconds) {
                server.workspaceBuildTargets().await()
            }
        }

    /**
     * Get source files and directories for the given build targets.
     */
    suspend fun buildTargetSources(params: SourcesParams): Either<BspError, SourcesResult> =
        withErrorHandling("buildTargetSources") {
            withTimeout(config.requestTimeout.inWholeMilliseconds) {
                server.buildTargetSources(params).await()
            }
        }

    /**
     * Find which build targets contain a given source file.
     * Only works if [BspCapabilities.supportsInverseSources] returns true.
     */
    suspend fun buildTargetInverseSources(params: InverseSourcesParams): Either<BspError, InverseSourcesResult> =
        withErrorHandling("buildTargetInverseSources") {
            if (!capabilities.supportsInverseSources()) {
                throw UnsupportedOperationException("Server does not support inverse sources")
            }
            withTimeout(config.requestTimeout.inWholeMilliseconds) {
                server.buildTargetInverseSources(params).await()
            }
        }

    /**
     * Get dependency modules (transitive dependencies) for build targets.
     * Only works if [BspCapabilities.supportsDependencyModules] returns true.
     */
    suspend fun buildTargetDependencyModules(
        params: DependencyModulesParams,
    ): Either<BspError, DependencyModulesResult> = withErrorHandling("buildTargetDependencyModules") {
        if (!capabilities.supportsDependencyModules()) {
            throw UnsupportedOperationException("Server does not support dependency modules")
        }
        withTimeout(config.requestTimeout.inWholeMilliseconds) {
            server.buildTargetDependencyModules(params).await()
        }
    }

    /**
     * Get dependency sources (e.g., downloaded JAR sources) for build targets.
     * Only works if [BspCapabilities.supportsDependencySources] returns true.
     */
    suspend fun buildTargetDependencySources(
        params: DependencySourcesParams,
    ): Either<BspError, DependencySourcesResult> = withErrorHandling("buildTargetDependencySources") {
        if (!capabilities.supportsDependencySources()) {
            throw UnsupportedOperationException("Server does not support dependency sources")
        }
        withTimeout(config.requestTimeout.inWholeMilliseconds) {
            server.buildTargetDependencySources(params).await()
        }
    }

    /**
     * Get resource files for build targets.
     * Only works if [BspCapabilities.supportsResources] returns true.
     */
    suspend fun buildTargetResources(params: ResourcesParams): Either<BspError, ResourcesResult> =
        withErrorHandling("buildTargetResources") {
            if (!capabilities.supportsResources()) {
                throw UnsupportedOperationException("Server does not support resources")
            }
            withTimeout(config.requestTimeout.inWholeMilliseconds) {
                server.buildTargetResources(params).await()
            }
        }

    /**
     * Get output paths (compiled class directories) for build targets.
     * Only works if [BspCapabilities.supportsOutputPaths] returns true.
     */
    suspend fun buildTargetOutputPaths(params: OutputPathsParams): Either<BspError, OutputPathsResult> =
        withErrorHandling("buildTargetOutputPaths") {
            if (!capabilities.supportsOutputPaths()) {
                throw UnsupportedOperationException("Server does not support output paths")
            }
            withTimeout(config.requestTimeout.inWholeMilliseconds) {
                server.buildTargetOutputPaths(params).await()
            }
        }

    /**
     * Compile the given build targets.
     * Only works if [BspCapabilities.supportsCompile] returns true.
     *
     * Note: This may take longer than standard requests, so consider using a longer timeout.
     */
    suspend fun buildTargetCompile(params: CompileParams): Either<BspError, CompileResult> =
        withErrorHandling("buildTargetCompile") {
            if (!capabilities.supportsCompile()) {
                throw UnsupportedOperationException("Server does not support compilation")
            }
            // NOTE: Compilation can take longer than standard requests (5 minutes minimum)
            val compileTimeout = maxOf(config.requestTimeout.inWholeMilliseconds, COMPILE_TIMEOUT_MS)
            withTimeout(compileTimeout) {
                server.buildTargetCompile(params).await()
            }
        }

    /**
     * Run tests for the given build targets.
     * Only works if [BspCapabilities.supportsTest] returns true.
     *
     * Note: Test execution may take longer than standard requests.
     */
    suspend fun buildTargetTest(params: TestParams): Either<BspError, TestResult> =
        withErrorHandling("buildTargetTest") {
            if (!capabilities.supportsTest()) {
                throw UnsupportedOperationException("Server does not support testing")
            }
            // NOTE: Test execution can take longer than standard requests (5 minutes minimum)
            val testTimeout = maxOf(config.requestTimeout.inWholeMilliseconds, TEST_TIMEOUT_MS)
            withTimeout(testTimeout) {
                server.buildTargetTest(params).await()
            }
        }

    /**
     * Run a program for the given build targets.
     * Only works if [BspCapabilities.supportsRun] returns true.
     */
    suspend fun buildTargetRun(params: RunParams): Either<BspError, RunResult> = withErrorHandling("buildTargetRun") {
        if (!capabilities.supportsRun()) {
            throw UnsupportedOperationException("Server does not support run")
        }
        withTimeout(config.requestTimeout.inWholeMilliseconds) {
            server.buildTargetRun(params).await()
        }
    }

    /**
     * Clean the build cache for the given targets.
     */
    suspend fun buildTargetCleanCache(params: CleanCacheParams): Either<BspError, CleanCacheResult> =
        withErrorHandling("buildTargetCleanCache") {
            withTimeout(config.requestTimeout.inWholeMilliseconds) {
                server.buildTargetCleanCache(params).await()
            }
        }

    /**
     * Reload the build after changes to build files (e.g., build.gradle, pom.xml).
     * Only works if [BspCapabilities.canReload] returns true.
     *
     * If the server doesn't support reload, a full reconnection is required.
     */
    suspend fun workspaceReload(): Either<BspError, Unit> = withErrorHandling("workspaceReload") {
        if (!capabilities.canReload()) {
            throw UnsupportedOperationException(
                "Server does not support reload - full reconnection required",
            )
        }
        withTimeout(config.requestTimeout.inWholeMilliseconds) {
            server.workspaceReload().await()
        }
    }

    /**
     * Get the capabilities wrapper for checking server features.
     */
    fun getCapabilities(): BspCapabilities = capabilities

    /**
     * Check if this connection has been closed.
     */
    fun isClosed(): Boolean = closed

    /**
     * Close the connection.
     * This should be called during server shutdown after buildShutdown() request.
     *
     * Note: This does NOT send shutdown/exit requests. Use lifecycle management for that.
     */
    override fun close() {
        if (!closed) {
            closed = true
            logger.debug { "BuildServerConnection closed" }
        }
    }

    /**
     * Wrap a BSP operation with error handling and logging.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional - catch all for BSP request errors
    private suspend fun <T> withErrorHandling(operation: String, block: suspend () -> T): Either<BspError, T> {
        checkNotClosed()
        return try {
            logger.trace { "Executing BSP operation: $operation" }
            block().right()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error(e) { "BSP operation timed out: $operation" }
            BspError.Timeout(operation, e).left()
        } catch (e: UnsupportedOperationException) {
            logger.warn { "BSP capability not supported: $operation" }
            BspError.UnsupportedCapability(operation, e.message).left()
        } catch (e: Exception) {
            logger.error(e) { "BSP operation failed: $operation" }
            BspError.RequestFailed(operation, e).left()
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "BuildServerConnection is closed" }
    }
}

/**
 * Sealed hierarchy of BSP errors for type-safe error handling.
 */
sealed class BspError {
    abstract val operation: String
    abstract val message: String

    /**
     * Request timed out waiting for server response.
     */
    data class Timeout(override val operation: String, val cause: Throwable) : BspError() {
        override val message: String = "Operation '$operation' timed out"
    }

    /**
     * Server does not support the requested capability.
     */
    data class UnsupportedCapability(override val operation: String, val reason: String?) : BspError() {
        override val message: String =
            "Operation '$operation' not supported${reason?.let { ": $it" } ?: ""}"
    }

    /**
     * Request failed due to server error or communication issue.
     */
    data class RequestFailed(override val operation: String, val cause: Throwable) : BspError() {
        override val message: String = "Operation '$operation' failed: ${cause.message}"
    }
}
