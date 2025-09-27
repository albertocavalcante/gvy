package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages a pool of Gradle project connections for improved performance.
 * Reuses existing connections and provides warm-up capabilities to reduce
 * cold start times when resolving dependencies.
 */
object GradleConnectionPool {
    private val logger = LoggerFactory.getLogger(GradleConnectionPool::class.java)

    // Thread-safe map of project directories to their connections
    private val connections = ConcurrentHashMap<Path, ProjectConnection>()

    /**
     * Gets or creates a Gradle project connection for the given directory.
     * Reuses existing connections for better performance.
     *
     * @param projectDir The project directory to connect to
     * @return A connected ProjectConnection
     */
    fun getConnection(projectDir: Path): ProjectConnection = connections.computeIfAbsent(projectDir) { dir ->
        logger.debug("Creating new Gradle connection for: $dir")
        createConnection(dir)
    }

    /**
     * Closes a specific connection and removes it from the pool.
     *
     * @param projectDir The project directory whose connection to close
     */
    fun closeConnection(projectDir: Path) {
        connections.remove(projectDir)?.let { connection ->
            try {
                connection.close()
                logger.debug("Closed Gradle connection for: $projectDir")
            } catch (e: Exception) {
                logger.warn("Error closing Gradle connection for $projectDir", e)
            }
        }
    }

    /**
     * Closes all connections and clears the pool.
     * Should be called during shutdown.
     */
    fun shutdown() {
        logger.info("Shutting down Gradle connection pool (${connections.size} connections)")

        connections.keys.toList().forEach { projectDir ->
            closeConnection(projectDir)
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
        val projects = connections.keys.map { it.fileName }
        return "GradleConnectionPool[connections=${connections.size}, projects=$projects]"
    }

    /**
     * Creates a new optimized Gradle connection for the project directory.
     */
    private fun createConnection(projectDir: Path): ProjectConnection {
        val projectFile = projectDir.toFile()

        require(projectFile.exists() && projectFile.isDirectory) {
            "Project directory does not exist: $projectDir"
        }

        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectFile)

        // Note: Gradle connector optimizations are handled by the Gradle daemon automatically

        return connector.connect()
    }
}
