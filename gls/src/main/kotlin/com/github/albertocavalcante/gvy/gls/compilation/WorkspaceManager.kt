package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
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
 *
 * The configuration is one immutable [WorkspaceModel] replaced atomically (H1): updates are serialized and
 * build the next model before publishing it, and every getter reads a single published model. Callers that
 * need several values from the same configuration read [current] once.
 */
@Suppress("TooManyFunctions")
class WorkspaceManager(
    private val fingerprintComputer: (dependencies: List<Path>, sourceRoots: List<Path>) -> String =
        ConfigurationFingerprint::compute,
) {
    private val logger = KotlinLogging.logger {}

    private val model = AtomicReference(emptyModel(null))

    // Serializes writers; readers never take it.
    private val updateLock = Any()

    // Project strategy registry for project-type-specific functionality
    @Volatile
    private var strategyRegistry: ProjectStrategyRegistry? = null

    /** The current workspace configuration: one consistent snapshot of root, classpath, roots and sources. */
    fun current(): WorkspaceModel = model.get()

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
        update { current ->
            val roots = current.sourceRoots.ifEmpty { discoverSourceRoots(workspaceRoot) }
            model(workspaceRoot, current.classpath, roots)
        }
    }

    fun updateDependencies(newDependencies: List<Path>): Boolean {
        var changed = false
        update { current ->
            if (classpathChanged(current.classpath, newDependencies)) {
                changed = true
                logger.info { "Updated dependency classpath with ${newDependencies.size} dependencies" }
                WorkspaceModel(current.root, newDependencies, current.sourceRoots, current.sources, fingerprintComputer)
            } else {
                logger.debug { "Dependencies unchanged" }
                current
            }
        }
        return changed
    }

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>): Boolean {
        var changed = false
        update { current ->
            val depsChanged = classpathChanged(current.classpath, dependencies)
            if (depsChanged) logger.info { "Updated dependency classpath with ${dependencies.size} dependencies" }
            val sourcesChanged = sourceDirectories.toSet() != current.sourceRoots.toSet()
            val roots = when {
                // A reordering of the same roots or classpath is not a change: keep the current order, so the
                // order-sensitive fingerprint agrees with the reported result and nothing diverges silently.
                sourceDirectories.isNotEmpty() && !sourcesChanged -> current.sourceRoots
                sourceDirectories.isNotEmpty() -> sourceDirectories.also {
                    logger.info { "Received ${it.distinct().size} source roots from build model" }
                }
                current.sourceRoots.isEmpty() -> discoverSourceRoots(workspaceRoot)
                else -> current.sourceRoots
            }
            changed = depsChanged || sourcesChanged
            model(workspaceRoot, if (depsChanged) dependencies else current.classpath, roots)
        }
        return changed
    }

    /** Builds the next model outside any reader's view (including disk walks) and publishes it whole. */
    private inline fun update(next: (WorkspaceModel) -> WorkspaceModel) {
        synchronized(updateLock) { model.set(next(model.get())) }
    }

    private fun model(root: Path?, classpath: List<Path>, sourceRoots: List<Path>) =
        WorkspaceModel(root, classpath, sourceRoots, scanSources(sourceRoots.distinct()), fingerprintComputer)

    private fun emptyModel(root: Path?) =
        WorkspaceModel(root, emptyList(), emptyList(), emptyList(), fingerprintComputer)

    private fun classpathChanged(current: List<Path>, next: List<Path>) =
        next.size != current.size || next.toSet() != current.toSet()

    private fun discoverSourceRoots(rootPath: Path): List<Path> {
        val sourceRoots = linkedSetOf<Path>()

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
        return sourceRoots.toList()
    }

    private fun scanSources(sourceRoots: List<Path>): List<Path> {
        val workspaceSources = sourceRoots.flatMap { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@flatMap emptyList<Path>()
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.extension.equals("groovy", ignoreCase = true) }
                    .sorted()
                    .toList()
            }
        }

        logger.info { "Indexed ${workspaceSources.size} Groovy sources from workspace roots" }
        workspaceSources.forEach { logger.debug { "Indexed source: $it" } }
        return workspaceSources
    }

    fun getDependencyClasspath(): List<Path> = current().classpath
    fun getWorkspaceRoot(): Path? = current().root
    fun getSourceRoots(): List<Path> = current().sourceRoots
    fun getWorkspaceSources(): List<Path> = current().sources

    /**
     * Computes a fingerprint of the current workspace configuration.
     * Used for cache coherency - when this changes, cached compilations may be stale.
     * Issue #743: Normalize parse modes and cache authority for cross-file resolution
     */
    fun getConfigurationFingerprint(): String = current().fingerprint

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
    fun getBoundedWorkspaceSources(boundedUris: Set<URI>): List<Path> =
        getBoundedWorkspaceSources(boundedUris, current())

    /** [getBoundedWorkspaceSources] against one [workspace] snapshot a compile already holds. */
    fun getBoundedWorkspaceSources(boundedUris: Set<URI>, workspace: WorkspaceModel): List<Path> {
        if (boundedUris.isEmpty()) return emptyList()

        val boundedPaths = boundedUris.mapNotNull { uri ->
            runCatching { Path.of(uri) }
                .getOrElse { throwable ->
                    rethrowIfCancellationOrError(throwable)
                    logger.debug(throwable) { "Failed to convert bounded URI to Path: $uri" }
                    null
                }
        }.toSet()

        return workspace.sources.filter { it in boundedPaths }
    }

    /**
     * Gets workspace source URIs for indexing.
     * Converts Path objects to URI objects for use in compilation service.
     */
    fun getWorkspaceSourceUris(): List<URI> = current().sources.mapNotNull { path ->
        runCatching { path.toUri() }
            .getOrElse { throwable ->
                rethrowIfCancellationOrError(throwable)
                logger.warn(throwable) { "Failed to convert path to URI: $path" }
                null
            }
    }

    /**
     * Gets the classpath for a file, including strategy-specific classpath if applicable.
     *
     * First queries active strategies for file-specific classpath. If a strategy returns
     * a non-null classpath, that is used. Otherwise falls back to the standard dependency
     * classpath.
     */
    fun getClasspathForFile(uri: URI, content: String): List<Path> = getClasspathForFile(uri, content, current())

    /** [getClasspathForFile] against one [workspace] snapshot a compile already holds. */
    fun getClasspathForFile(uri: URI, content: String, workspace: WorkspaceModel): List<Path> {
        // One immutable classpath: a strategy may retain it without seeing later updates
        val dependencies = workspace.classpath
        strategyRegistry?.activeStrategies?.forEach { strategy ->
            val classpath = strategy.getClasspathForFile(uri, content, dependencies)
            if (classpath != null) {
                return classpath
            }
        }

        // Return standard dependency classpath for non-handled files
        return dependencies
    }
}
