package com.github.groovylsp.bsp.lifecycle

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.TestResult
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Path

/**
 * Main BSP session class that manages the lifecycle of a build server connection.
 *
 * This class provides:
 * - High-level operations (compile, test, reload)
 * - Build target management and caching
 * - Diagnostic event handling
 * - Automatic resource cleanup
 *
 * Usage:
 * ```
 * val session = BspConnector(workspace).connect().getOrThrow()
 * session.use { bsp ->
 *     bsp.onDiagnostics { diagnostics ->
 *         println("Got diagnostics for ${diagnostics.textDocument.uri}")
 *     }
 *     val result = bsp.compile(targetIds)
 * }
 * ```
 */
class BspSession(
    val connection: BuildServerConnection,
    val buildTargets: BuildTargetCache,
    private val client: BspClientHandler,
) : Closeable {
    private val logger = LoggerFactory.getLogger(BspSession::class.java)

    @Volatile
    private var closed = false

    /**
     * Compile the specified build targets.
     *
     * @param targetIds List of targets to compile
     * @return Either an error or the compilation result
     */
    suspend fun compile(targetIds: List<BuildTargetIdentifier>): Either<SessionError, CompileResult> {
        ensureNotClosed()

        if (targetIds.isEmpty()) {
            return SessionError.InvalidOperation("No targets to compile").left()
        }

        logger.info("Compiling ${targetIds.size} targets")
        return connection.compile(targetIds).mapLeft { error ->
            SessionError.OperationFailed("Compilation failed", error)
        }
    }

    /**
     * Compile the file at the given path by finding its containing build targets.
     *
     * @param file Path to the file to compile
     * @return Either an error or the compilation result
     */
    suspend fun compileFile(file: Path): Either<SessionError, CompileResult> {
        ensureNotClosed()

        val targets = buildTargets.findRelevantTargets(file)
        if (targets.isEmpty()) {
            logger.warn("No build targets found for file: $file")
            return SessionError.NoTargetsFound("No build targets contain file: $file").left()
        }

        logger.info("Found ${targets.size} targets for file $file")
        return compile(targets)
    }

    /**
     * Run tests for the specified build targets.
     *
     * @param targetIds List of targets to test
     * @return Either an error or the test result
     */
    suspend fun test(targetIds: List<BuildTargetIdentifier>): Either<SessionError, TestResult> {
        ensureNotClosed()

        if (targetIds.isEmpty()) {
            return SessionError.InvalidOperation("No targets to test").left()
        }

        logger.info("Testing ${targetIds.size} targets")
        return connection.test(targetIds).mapLeft { error ->
            SessionError.OperationFailed("Test execution failed", error)
        }
    }

    /**
     * Reload build targets from the server and refresh the cache.
     *
     * This is typically called when build configuration changes (e.g., build.gradle modified).
     */
    suspend fun reload(): Either<SessionError, Unit> {
        ensureNotClosed()

        logger.info("Reloading build targets")

        return connection.workspaceBuildTargets()
            .mapLeft { error -> SessionError.OperationFailed("Failed to reload targets", error) }
            .flatMap { targetsResult ->
                val targets = targetsResult.targets ?: emptyList()
                buildTargets.updateTargets(targets)

                // Refresh source mappings
                val targetIds = targets.map { it.id }
                connection.buildTargetSources(targetIds)
                    .mapLeft { error -> SessionError.OperationFailed("Failed to reload sources", error) }
                    .map { sourcesResult ->
                        val sourcesMap = sourcesResult.items
                            ?.associateBy({ it.target }, { it.sources ?: emptyList() })
                            ?: emptyMap()
                        buildTargets.updateSourceMappings(sourcesMap)
                        logger.info("Build targets reloaded: ${targets.size} targets")
                    }
            }
    }

    /**
     * Register a diagnostics handler to receive compilation errors/warnings.
     */
    fun onDiagnostics(handler: (PublishDiagnosticsParams) -> Unit) {
        client.onDiagnostics(handler)
    }

    /**
     * Clean the build cache for the specified targets.
     */
    suspend fun cleanCache(targetIds: List<BuildTargetIdentifier>): Either<SessionError, Unit> {
        ensureNotClosed()

        logger.info("Cleaning cache for ${targetIds.size} targets")
        return connection.cleanCache(targetIds)
            .mapLeft { error -> SessionError.OperationFailed("Cache clean failed", error) }
            .map { Unit }
    }

    /**
     * Clean the build cache for all targets.
     */
    suspend fun cleanAllCaches(): Either<SessionError, Unit> {
        ensureNotClosed()
        val allTargets = buildTargets.getAllTargetIds()
        return cleanCache(allTargets)
    }

    /**
     * Get all build targets.
     */
    fun getAllTargets() = buildTargets.getAllTargets()

    /**
     * Find targets that contain the given source file.
     */
    fun findTargetsForFile(file: Path) = buildTargets.findRelevantTargets(file)

    /**
     * Check if the session is still active.
     */
    fun isActive(): Boolean = !closed && connection.isAlive()

    override fun close() {
        if (closed) return

        logger.info("Closing BSP session")
        closed = true

        try {
            client.clearListeners()
            connection.close()
        } catch (e: Exception) {
            logger.error("Error closing BSP session: ${e.message}", e)
        }
    }

    private fun ensureNotClosed() {
        check(!closed) { "BspSession is closed" }
        check(connection.isAlive()) { "Build server process is not alive" }
    }

    /**
     * Errors that can occur during session operations.
     */
    sealed class SessionError(val message: String) {
        data class OperationFailed(val operation: String, val cause: BuildServerConnection.BspError) :
            SessionError("$operation: ${cause.message}")

        data class InvalidOperation(val reason: String) : SessionError(reason)

        data class NoTargetsFound(val reason: String) : SessionError(reason)

        override fun toString(): String = "SessionError: $message"
    }
}
