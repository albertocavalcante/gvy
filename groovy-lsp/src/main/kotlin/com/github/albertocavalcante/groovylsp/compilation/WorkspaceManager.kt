package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.project.JenkinsCapabilities
import com.github.albertocavalcante.groovylsp.project.ProjectStrategyRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * Manages the workspace environment for the compilation service.
 *
 * Responsibilities include:
 * - Tracking the workspace root directory.
 * - Managing the dependency classpath for compilation.
 * - Indexing and maintaining source root directories.
 * - Collecting and indexing Groovy source files within the workspace.
 *
 * This class serves as a central point for workspace state, allowing other components
 * of the compilation service to query and update workspace-related information.
 */
@Suppress("TooManyFunctions")
class WorkspaceManager {
    private val logger = KotlinLogging.logger {}

    // Dependency classpath management
    private val dependencyClasspath = mutableListOf<Path>()
    private var workspaceRoot: Path? = null
    private val sourceRoots = mutableSetOf<Path>()
    private var workspaceSources: List<Path> = emptyList()

    // Project strategy registry for project-type-specific functionality
    private var strategyRegistry: ProjectStrategyRegistry? = null

    /**
     * Sets the project strategy registry.
     * The registry manages project-type-specific strategies (Jenkins, Gradle, etc.).
     */
    fun setStrategyRegistry(registry: ProjectStrategyRegistry) {
        this.strategyRegistry = registry
        logger.info { "Strategy registry set with ${registry.activeStrategies.size} strategies" }
    }

    /**
     * Gets the project strategy registry.
     */
    fun getStrategyRegistry(): ProjectStrategyRegistry? = strategyRegistry

    /**
     * Gets Jenkins capabilities from the strategy registry.
     * This is the preferred way to access Jenkins-specific functionality.
     *
     * @return JenkinsCapabilities if a Jenkins strategy is active, null otherwise
     */
    fun getJenkinsCapabilities(): JenkinsCapabilities? = strategyRegistry?.findCapability<JenkinsCapabilities>()

    fun initializeWorkspace(workspaceRoot: Path) {
        logger.info { "Initializing workspace (non-blocking): $workspaceRoot" }
        this.workspaceRoot = workspaceRoot
        refreshSourceRoots(workspaceRoot)
        refreshWorkspaceSources()
    }

    fun updateDependencies(newDependencies: List<Path>): Boolean {
        var changed = false
        synchronized(dependencyClasspath) {
            if (newDependencies.size != dependencyClasspath.size ||
                newDependencies.toSet() != dependencyClasspath.toSet()
            ) {
                dependencyClasspath.clear()
                dependencyClasspath.addAll(newDependencies)
                changed = true
                logger.info { "Updated dependency classpath with ${dependencyClasspath.size} dependencies" }
            } else {
                logger.debug { "Dependencies unchanged" }
            }
        }
        return changed
    }

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>): Boolean {
        this.workspaceRoot = workspaceRoot

        val depsChanged = synchronized(dependencyClasspath) {
            val changed =
                dependencies.size != dependencyClasspath.size || dependencies.toSet() != dependencyClasspath.toSet()
            if (changed) {
                dependencyClasspath.clear()
                dependencyClasspath.addAll(dependencies)
                logger.info { "Updated dependency classpath with ${dependencyClasspath.size} dependencies" }
            }
            changed
        }

        val sourcesChanged = sourceDirectories.toSet() != sourceRoots
        if (sourceDirectories.isNotEmpty()) {
            sourceRoots.clear()
            sourceDirectories.forEach(sourceRoots::add)
            logger.info { "Received ${sourceRoots.size} source roots from build model" }
        } else if (sourceRoots.isEmpty()) {
            refreshSourceRoots(workspaceRoot)
        }

        refreshWorkspaceSources()

        return depsChanged || sourcesChanged
    }

    private fun refreshSourceRoots(rootPath: Path) {
        if (sourceRoots.isNotEmpty()) return

        // Standard Maven/Gradle source directories
        val standardSourceDirs = listOf(
            rootPath.resolve("src/main/groovy"),
            rootPath.resolve("src/main/java"),
            rootPath.resolve("src/main/kotlin"),
            rootPath.resolve("src/test/groovy"),
        )

        val existingStandardDirs = standardSourceDirs.filter { Files.exists(it) && Files.isDirectory(it) }

        if (existingStandardDirs.isNotEmpty()) {
            existingStandardDirs.forEach(sourceRoots::add)
        }

        // Jenkins shared libraries and other layouts often use test/groovy directly.
        val extraSourceDirs = listOf(
            rootPath.resolve("test/groovy"),
        ).filter { Files.exists(it) && Files.isDirectory(it) }

        extraSourceDirs.forEach(sourceRoots::add)

        if (existingStandardDirs.isEmpty()) {
            // Jenkins Shared Library structure: bare src/ directory
            // TODO(#701): Improve heuristics for detecting source roots in Light Mode
            //   See: https://github.com/albertocavalcante/gvy/issues/701
            val bareSrcDir = rootPath.resolve("src")
            if (Files.exists(bareSrcDir) && Files.isDirectory(bareSrcDir)) {
                sourceRoots.add(bareSrcDir)
            }
        }

        // Add source roots from active strategies
        strategyRegistry?.activeStrategies?.forEach { strategy ->
            strategy.getSourceRoots().forEach { sourceRoot ->
                if (Files.exists(sourceRoot) && Files.isDirectory(sourceRoot)) {
                    sourceRoots.add(sourceRoot)
                    logger.debug { "Added source root from strategy '${strategy.id}': $sourceRoot" }
                }
            }
        }

        logger.info { "Indexed ${sourceRoots.size} source roots: ${sourceRoots.joinToString { it.toString() }}" }
    }

    private fun refreshWorkspaceSources() {
        workspaceSources = sourceRoots.flatMap { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@flatMap emptyList<Path>()
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.extension.equals("groovy", ignoreCase = true) }
                    .sorted()
                    .toList()
            }
        }

        logger.info { "Indexed ${workspaceSources.size} Groovy sources from workspace roots" }
        workspaceSources.forEach { logger.info { "Indexed source: $it" } }
    }

    fun getDependencyClasspath(): List<Path> = dependencyClasspath.toList()
    fun getWorkspaceRoot(): Path? = workspaceRoot
    fun getSourceRoots(): List<Path> = sourceRoots.toList()
    fun getWorkspaceSources(): List<Path> = workspaceSources

    /**
     * Computes a fingerprint of the current workspace configuration.
     * Used for cache coherency - when this changes, cached compilations may be stale.
     * Issue #743: Normalize parse modes and cache authority for cross-file resolution
     */
    fun getConfigurationFingerprint(): String =
        ConfigurationFingerprint.compute(dependencyClasspath.toList(), sourceRoots.toList())

    /**
     * Gets bounded workspace sources filtered by the given URIs.
     *
     * This enables O(k) compilation instead of O(n) by only including relevant files
     * based on dependency information from the DependencyGraph.
     *
     * Issue #743: Normalize parse modes and cache authority for cross-file resolution
     *
     * @param boundedUris Set of URIs to filter workspace sources by
     * @return List of paths that match the bounded URIs
     */
    fun getBoundedWorkspaceSources(boundedUris: Set<URI>): List<Path> {
        if (boundedUris.isEmpty()) return emptyList()

        val boundedPaths = boundedUris.mapNotNull { uri ->
            runCatching { Path.of(uri) }
                .getOrElse { throwable ->
                    rethrowIfCancellationOrError(throwable)
                    logger.debug(throwable) { "Failed to convert bounded URI to Path: $uri" }
                    null
                }
        }.toSet()

        return workspaceSources.filter { it in boundedPaths }
    }

    /**
     * Gets workspace source URIs for indexing.
     * Converts Path objects to URI objects for use in compilation service.
     */
    fun getWorkspaceSourceUris(): List<URI> = workspaceSources.mapNotNull { path ->
        runCatching { path.toUri() }
            .getOrElse { throwable ->
                rethrowIfCancellationOrError(throwable)
                logger.warn(throwable) { "Failed to convert path to URI: $path" }
                null
            }
    }

    private fun rethrowIfCancellationOrError(throwable: Throwable) {
        when (throwable) {
            is CancellationException -> throw throwable
            is Error -> throw throwable
        }
    }

    /**
     * Gets the classpath for a file, including strategy-specific classpath if applicable.
     *
     * First queries active strategies for file-specific classpath. If a strategy returns
     * a non-null classpath, that is used. Otherwise falls back to the standard dependency
     * classpath.
     */
    fun getClasspathForFile(uri: URI, content: String): List<Path> {
        // Try strategies first
        strategyRegistry?.activeStrategies?.forEach { strategy ->
            val classpath = strategy.getClasspathForFile(uri, content, dependencyClasspath)
            if (classpath != null) {
                return classpath
            }
        }

        // Return standard dependency classpath for non-handled files
        return dependencyClasspath.toList()
    }
}
