// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.concurrent.SharedValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

private const val SWEEP_INTERVAL_SECONDS = 60L

/**
 * The language server's pool of Gradle project connections, which connects through the Tooling API. Every member
 * forwards to one [ProjectConnectionPool], which documents the contract; tests build pools of their own instead of
 * sharing this one.
 */
object GradleConnectionPool : LeasingConnectionFactory {
    private val pool = ProjectConnectionPool(connect = ::createConnection)

    fun configure(newSettings: GradleDaemonSettings) = pool.configure(newSettings)

    override fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection =
        pool.getConnection(projectDir, gradleUserHomeDir)

    override fun acquire(projectDir: Path, gradleUserHomeDir: File?): ConnectionLease =
        pool.acquire(projectDir, gradleUserHomeDir)

    fun closeConnection(projectDir: Path) = pool.closeConnection(projectDir)

    fun shutdown() = pool.shutdown()

    fun getActiveConnectionCount(): Int = pool.getActiveConnectionCount()

    fun getStats(): String = pool.getStats()

    /**
     * Creates a new optimized Gradle connection for the project directory.
     */
    private fun createConnection(projectDir: Path, gradleUserHomeDir: Path?): ProjectConnection {
        val projectFile = projectDir.toFile()

        require(projectFile.exists() && projectFile.isDirectory) {
            "Project directory does not exist: $projectDir"
        }

        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectFile)

        if (gradleUserHomeDir != null) {
            connector.useGradleUserHomeDir(gradleUserHomeDir.toFile())
        }

        return connector.connect()
    }
}

/**
 * Manages a pool of Gradle project connections for improved performance.
 * Reuses existing connections and provides warm-up capabilities to reduce
 * cold start times when resolving dependencies.
 *
 * Connections unused for [GradleDaemonSettings.connectionIdleTimeout] are closed by a background sweep (and all of
 * them on [shutdown]). Closing a connection drops the client-side Tooling API state only; the daemon's own lifetime
 * is bounded by the idle timeout the resolver passes with each operation.
 *
 * @param connect opens a connection for a project directory and an optional Gradle user home
 */
internal class ProjectConnectionPool(
    private val connect: (projectDir: Path, gradleUserHomeDir: Path?) -> ProjectConnection,
) : LeasingConnectionFactory {
    private data class ConnectionKey(val projectDir: Path, val gradleUserHomeDir: Path?)

    private val settings = SharedValue(GradleDaemonSettings())

    // Thread-safe cache of project directories (and optional user home) to their connections
    private val connections = IdleReleasingCache<ConnectionKey, ProjectConnection>(
        create = { key ->
            logger.debug {
                "Creating new Gradle connection for: ${key.projectDir} " +
                    "(userHome=${key.gradleUserHomeDir?.fileName ?: "default"})"
            }
            connect(key.projectDir, key.gradleUserHomeDir)
        },
        close = ::closeQuietly,
        clockNanos = System::nanoTime,
        idleTimeout = { settings.value.connectionIdleTimeout },
    )

    private var sweeper: ScheduledExecutorService? = null

    /** Set by [shutdown]; a pool that is shut down keeps no connections and runs no sweeper. */
    private val closed = SharedValue(false)

    /** Whether the idle sweep is scheduled. */
    @get:Synchronized
    val isSweeping: Boolean get() = sweeper != null

    /** Applies [newSettings]; only the connection idle timeout is used here, and it applies from the next sweep. */
    fun configure(newSettings: GradleDaemonSettings) {
        settings.set(newSettings)
        logger.info { "Gradle daemon settings: $newSettings" }
    }

    /**
     * Gets or creates a Gradle project connection for the given directory.
     * Reuses existing connections for better performance.
     *
     * The connection may be closed by the idle sweep once unused; callers that run long operations should prefer
     * [acquire], which keeps it open for the duration.
     *
     * @param projectDir The project directory to connect to
     * @param gradleUserHomeDir Optional Gradle user home directory to isolate from user init scripts
     * @return A connected ProjectConnection
     */
    override fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection {
        check(!closed.value) { "Gradle connection pool is shut down" }
        ensureSweeper()
        return connections.get(keyOf(projectDir, gradleUserHomeDir))
    }

    /**
     * Borrows a pooled connection until the lease is closed. After [shutdown] the pool no longer retains anything:
     * the lease wraps a private connection that is closed with the lease, and the sweeper is not restarted.
     */
    override fun acquire(projectDir: Path, gradleUserHomeDir: File?): ConnectionLease {
        if (closed.value) {
            val key = keyOf(projectDir, gradleUserHomeDir)
            val connection = connect(key.projectDir, key.gradleUserHomeDir)
            return ConnectionLease(connection) { closeQuietly(key, connection) }
        }
        ensureSweeper()
        val lease = connections.acquire(keyOf(projectDir, gradleUserHomeDir))
        return ConnectionLease(lease.value) { lease.close() }
    }

    private fun keyOf(projectDir: Path, gradleUserHomeDir: File?) = ConnectionKey(
        projectDir = projectDir.toAbsolutePath().normalize(),
        gradleUserHomeDir = gradleUserHomeDir?.toPath()?.toAbsolutePath()?.normalize(),
    )

    @Synchronized
    private fun ensureSweeper() {
        if (sweeper != null || closed.value) return
        sweeper = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gradle-connection-idle-sweep").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    runCatching { connections.releaseIdle() }
                        .onFailure { logger.warn(it) { "Idle Gradle connection sweep failed" } }
                },
                SWEEP_INTERVAL_SECONDS,
                SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    @Synchronized
    private fun stopSweeper() {
        sweeper?.shutdownNow()
        sweeper = null
    }

    /**
     * Closes a specific connection and removes it from the pool.
     *
     * @param projectDir The project directory whose connection to close
     */
    fun closeConnection(projectDir: Path) {
        val normalizedProjectDir = projectDir.toAbsolutePath().normalize()
        connections.remove { it.projectDir == normalizedProjectDir }
    }

    @Suppress("TooGenericExceptionCaught") // closing must never throw: a failure may not strand other entries
    private fun closeQuietly(key: ConnectionKey, connection: ProjectConnection) {
        try {
            connection.close()
            logger.debug { "Closed Gradle connection for: ${key.projectDir}" }
        } catch (e: GradleConnectionException) {
            logger.warn { "Gradle connection error while closing for ${key.projectDir}: ${e.message}" }
        } catch (e: IllegalStateException) {
            logger.warn { "Illegal state while closing connection for ${key.projectDir}: ${e.message}" }
        } catch (e: RuntimeException) {
            // For example an UncheckedException from a Tooling API executor interrupted during shutdown.
            logger.warn(e) { "Failed to close Gradle connection for ${key.projectDir}" }
        }
    }

    /**
     * Closes all connections and clears the pool, and stops the sweeper. Should be called during shutdown. Afterwards
     * [getConnection] throws and [acquire] hands out connections that are closed with their lease.
     */
    fun shutdown() {
        logger.info { "Shutting down Gradle connection pool (${connections.size} connections)" }

        closed.set(true)
        stopSweeper()
        connections.clear()
        logger.info { "Gradle connection pool shutdown complete" }
    }

    /**
     * Gets the number of active connections in the pool.
     */
    fun getActiveConnectionCount(): Int = connections.size

    /**
     * Gets connection statistics for debugging.
     */
    fun getStats(): String {
        val projects = connections.keys.map { it.projectDir.fileName }
        return "GradleConnectionPool[connections=${connections.size}, projects=$projects]"
    }
}
