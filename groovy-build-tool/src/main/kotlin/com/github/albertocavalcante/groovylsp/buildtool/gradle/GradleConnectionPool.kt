package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages a pool of Gradle project connections for improved performance.
 * Reuses existing connections and provides warm-up capabilities to reduce
 * cold start times when resolving dependencies.
 */
object GradleConnectionPool : GradleConnectionFactory {
    private val logger = LoggerFactory.getLogger(GradleConnectionPool::class.java)

    private data class ConnectionKey(val projectDir: Path, val gradleUserHomeDir: Path?)

    // Thread-safe map of project directories (and optional user home) to their connections
    private val connections = ConcurrentHashMap<ConnectionKey, ProjectConnection>()

    /**
     * Gets or creates a Gradle project connection for the given directory.
     * Reuses existing connections for better performance.
     *
     * @param projectDir The project directory to connect to
     * @param gradleUserHomeDir Optional Gradle user home directory to isolate from user init scripts
     * @return A connected ProjectConnection
     */
    override fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection {
        val normalizedProjectDir = projectDir.toAbsolutePath().normalize()
        val normalizedUserHome = gradleUserHomeDir?.toPath()?.toAbsolutePath()?.normalize()
        val key = ConnectionKey(projectDir = normalizedProjectDir, gradleUserHomeDir = normalizedUserHome)

        return connections.computeIfAbsent(key) { createdKey ->
            logger.debug(
                "Creating new Gradle connection for: ${createdKey.projectDir} " +
                    "(userHome=${createdKey.gradleUserHomeDir?.fileName ?: "default"})",
            )
            createConnection(createdKey.projectDir, createdKey.gradleUserHomeDir)
        }
    }

    /**
     * Closes a specific connection and removes it from the pool.
     *
     * @param projectDir The project directory whose connection to close
     */
    // Generic exception handling removed
    fun closeConnection(projectDir: Path) {
        val normalizedProjectDir = projectDir.toAbsolutePath().normalize()
        val keys = connections.keys.filter { it.projectDir == normalizedProjectDir }
        keys.forEach { key ->
            closeConnection(key)
        }
    }

    private fun closeConnection(key: ConnectionKey) {
        connections.remove(key)?.let { connection ->
            try {
                connection.close()
                logger.debug("Closed Gradle connection for: ${key.projectDir}")
            } catch (e: GradleConnectionException) {
                logger.warn("Gradle connection error while closing for ${key.projectDir}: ${e.message}")
            } catch (e: IllegalStateException) {
                logger.warn("Illegal state while closing connection for ${key.projectDir}: ${e.message}")
            }
        }
    }

    /**
     * Closes all connections and clears the pool.
     * Should be called during shutdown.
     */
    fun shutdown() {
        logger.info("Shutting down Gradle connection pool (${connections.size} connections)")

        connections.keys.toList().forEach { key ->
            closeConnection(key)
        }

        connections.clear()
        logger.info("Gradle connection pool shutdown complete")
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

        // Note: Gradle connector optimizations are handled by the Gradle daemon automatically

        return connector.connect()
    }
}
