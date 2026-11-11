package com.github.albertocavalcante.gvy.build.gradle

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages a pool of Gradle project connections for improved performance.
 * Reuses existing connections and provides warm-up capabilities to reduce
 * cold start times when resolving dependencies.
 *
 * Connections unused for [GradleDaemonSettings.connectionIdleTimeout] are closed by a background sweep (and all of
 * them on [shutdown]). Closing a connection drops the client-side Tooling API state only; the daemon's own lifetime
 * is bounded by the idle timeout the resolver passes with each operation.
 */
object GradleConnectionPool : LeasingConnectionFactory {
    private val logger = KotlinLogging.logger {}

    private const val SWEEP_INTERVAL_SECONDS = 60L

    private data class ConnectionKey(val projectDir: Path, val gradleUserHomeDir: Path?)

    @Volatile
    private var settings = GradleDaemonSettings()

    // Thread-safe cache of project directories (and optional user home) to their connections
    private val connections = IdleReleasingCache<ConnectionKey, ProjectConnection>(
        create = { key ->
            logger.debug {
                "Creating new Gradle connection for: ${key.projectDir} " +
                    "(userHome=${key.gradleUserHomeDir?.fileName ?: "default"})"
            }
            createConnection(key.projectDir, key.gradleUserHomeDir)
        },
        close = ::closeQuietly,
        clockNanos = System::nanoTime,
        idleTimeout = { settings.connectionIdleTimeout },
    )

    private var sweeper: ScheduledExecutorService? = null

    /** Applies [newSettings]; only the connection idle timeout is used here, and it applies from the next sweep. */
    fun configure(newSettings: GradleDaemonSettings) {
        settings = newSettings
        logger.info { "Gradle daemon settings: $newSettings" }
    }

    /**
     * Gets or creates a Gradle project connection for the given directory.
     * Reuses existing connections for better performance.
     *
     * The connection may be closed by the idle sweep once unused; callers that run long operations should prefer
     * [withConnection], which keeps it open for the duration.
     *
     * @param projectDir The project directory to connect to
     * @param gradleUserHomeDir Optional Gradle user home directory to isolate from user init scripts
     * @return A connected ProjectConnection
     */
    override fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection {
        ensureSweeper()
        return connections.get(keyOf(projectDir, gradleUserHomeDir))
    }

    override fun <T> withConnection(projectDir: Path, gradleUserHomeDir: File?, block: (ProjectConnection) -> T): T {
        ensureSweeper()
        return connections.lease(keyOf(projectDir, gradleUserHomeDir), block)
    }

    private fun keyOf(projectDir: Path, gradleUserHomeDir: File?) = ConnectionKey(
        projectDir = projectDir.toAbsolutePath().normalize(),
        gradleUserHomeDir = gradleUserHomeDir?.toPath()?.toAbsolutePath()?.normalize(),
    )

    @Synchronized
    private fun ensureSweeper() {
        if (sweeper != null) return
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

    private fun closeQuietly(key: ConnectionKey, connection: ProjectConnection) {
        try {
            connection.close()
            logger.debug { "Closed Gradle connection for: ${key.projectDir}" }
        } catch (e: GradleConnectionException) {
            logger.warn { "Gradle connection error while closing for ${key.projectDir}: ${e.message}" }
        } catch (e: IllegalStateException) {
            logger.warn { "Illegal state while closing connection for ${key.projectDir}: ${e.message}" }
        }
    }

    /**
     * Closes all connections and clears the pool.
     * Should be called during shutdown.
     */
    fun shutdown() {
        logger.info { "Shutting down Gradle connection pool (${connections.size} connections)" }

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
