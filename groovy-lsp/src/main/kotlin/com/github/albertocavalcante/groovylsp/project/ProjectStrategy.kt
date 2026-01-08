package com.github.albertocavalcante.groovylsp.project

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import kotlinx.coroutines.Job
import java.net.URI
import java.nio.file.Path

/**
 * Strategy interface for project-type-specific initialization and behavior.
 *
 * Each implementation handles a specific project type (Jenkins, Gradle, Maven, etc.)
 * and provides classpath, source roots, and metadata specific to that project type.
 *
 * Multiple strategies can be active simultaneously (e.g., Jenkins + Gradle in a mixed workspace).
 * Strategies are selected based on [canHandle] returning true for the workspace.
 *
 * @see ProjectStrategyRegistry for strategy management and selection
 * @see JenkinsCapabilities for the Jenkins-specific capability interface
 */
interface ProjectStrategy {
    /** Unique identifier for this strategy (e.g., "jenkins", "gradle", "maven") */
    val id: String

    /** Human-readable name for logging and UI */
    val displayName: String

    /** Priority for strategy selection (higher = checked first). Default is 0. */
    val priority: Int get() = 0

    /**
     * Determines if this strategy can handle the given workspace.
     *
     * @param workspaceRoot The root directory of the workspace
     * @param config Server configuration
     * @return true if this strategy should be active for the workspace
     */
    fun canHandle(workspaceRoot: Path, config: ServerConfiguration): Boolean

    /**
     * Initializes the strategy for the workspace.
     * Called once during workspace initialization after [canHandle] returns true.
     *
     * @param workspaceRoot The root directory of the workspace
     * @param config Server configuration
     * @return A Job for async initialization, or null if initialization is synchronous
     */
    suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job?

    /**
     * Gets additional source roots provided by this strategy.
     * For example, Jenkins shared library source directories.
     *
     * @return List of additional source root paths, empty if none
     */
    fun getSourceRoots(): List<Path> = emptyList()

    /**
     * Gets additional classpath entries for a specific file.
     *
     * @param uri The file URI
     * @param content The file content
     * @param projectDependencies The base project dependencies
     * @return Classpath entries for this file, or null if this strategy doesn't handle this file
     */
    fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path>? = null

    /**
     * Called when configuration changes.
     *
     * @param config The new server configuration
     */
    fun updateConfiguration(config: ServerConfiguration) {}

    /**
     * Called when the workspace is shutting down.
     * Use this to cancel any async jobs and clean up resources.
     */
    fun shutdown() {}
}
