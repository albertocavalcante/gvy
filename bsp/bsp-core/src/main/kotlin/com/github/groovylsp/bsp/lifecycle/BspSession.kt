package com.github.groovylsp.bsp.lifecycle

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.TestResult
import com.github.groovylsp.bsp.client.BuildServerConnection
import com.github.groovylsp.bsp.model.BuildTargetCache
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val serverProcess: Process,
) : Closeable {
    private val logger = KotlinLogging.logger {}

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

        logger.info { "Compiling ${targetIds.size} targets" }
        return connection.buildTargetCompile(ch.epfl.scala.bsp4j.CompileParams(targetIds)).mapLeft { error ->
            SessionError.OperationFailed("Compilation failed", error.message)
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

        val target = buildTargets.findTargetForSource(file)
        if (target == null) {
            logger.warn { "No build targets found for file: $file" }
            return SessionError.NoTargetsFound("No build targets contain file: $file").left()
        }

        logger.info { "Found target ${target.id.uri} for file $file" }
        return compile(listOf(target.id))
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

        logger.info { "Testing ${targetIds.size} targets" }
        return connection.buildTargetTest(ch.epfl.scala.bsp4j.TestParams(targetIds)).mapLeft { error ->
            SessionError.OperationFailed("Test execution failed", error.message)
        }
    }

    /**
     * Reload build targets from the server and refresh the cache.
     *
     * This is typically called when build configuration changes (e.g., build.gradle modified).
     */
    suspend fun reload(): Either<SessionError, Unit> {
        ensureNotClosed()

        logger.info { "Reloading build targets" }

        return connection.workspaceBuildTargets()
            .mapLeft { error -> SessionError.OperationFailed("Failed to reload targets", error.message) }
            .flatMap { targetsResult ->
                val targets = targetsResult.targets ?: emptyList()
                buildTargets.updateTargets(targets)

                // Refresh source mappings for each target
                val targetIds = targets.map { it.id }
                connection.buildTargetSources(SourcesParams(targetIds))
                    .mapLeft { error -> SessionError.OperationFailed("Failed to reload sources", error.message) }
                    .map { sourcesResult ->
                        sourcesResult.items?.forEach { item ->
                            val sources = item.sources?.mapNotNull { sourceItem ->
                                @Suppress("TooGenericExceptionCaught") // URI parsing failures
                                try {
                                    java.nio.file.Paths.get(java.net.URI.create(sourceItem.uri))
                                } catch (e: Exception) {
                                    logger.debug { "Failed to parse source URI: ${sourceItem.uri}" }
                                    null
                                }
                            } ?: emptyList()
                            buildTargets.updateSources(item.target, sources)
                        }
                        logger.info { "Build targets reloaded: ${targets.size} targets" }
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

        logger.info { "Cleaning cache for ${targetIds.size} targets" }
        return connection.buildTargetCleanCache(ch.epfl.scala.bsp4j.CleanCacheParams(targetIds))
            .mapLeft { error -> SessionError.OperationFailed("Cache clean failed", error.message) }
            .map { Unit }
    }

    /**
     * Clean the build cache for all targets.
     */
    suspend fun cleanAllCaches(): Either<SessionError, Unit> {
        ensureNotClosed()
        val allTargets = buildTargets.all().map { it.id }
        return cleanCache(allTargets)
    }

    /**
     * Get all build targets.
     */
    fun getAllTargets() = buildTargets.all()

    /**
     * Find targets that contain the given source file.
     */
    fun findTargetsForFile(file: Path) = buildTargets.findTargetForSource(file)?.let { listOf(it) } ?: emptyList()

    /**
     * Check if the session is still active.
     */
    fun isActive(): Boolean = !closed && !connection.isClosed() && serverProcess.isAlive

    override fun close() {
        if (closed) return

        logger.info { "Closing BSP session" }
        closed = true
        @Suppress("TooGenericExceptionCaught") // Cleanup resilience
        try {
            client.clearListeners()
            connection.close()
            if (serverProcess.isAlive) {
                serverProcess.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error closing BSP session: ${e.message}" }
        }
    }

    private fun ensureNotClosed() {
        check(!closed) { "BspSession is closed" }
        check(!connection.isClosed()) { "BuildServerConnection is closed" }
        check(serverProcess.isAlive) { "Build server process is not alive" }
    }

    /**
     * Errors that can occur during session operations.
     */
    sealed class SessionError(val message: String) {
        data class OperationFailed(val operation: String, val reason: String) :
            SessionError("$operation: $reason")

        data class InvalidOperation(val reason: String) : SessionError(reason)

        data class NoTargetsFound(val reason: String) : SessionError(reason)

        override fun toString(): String = "SessionError: $message"
    }
}
