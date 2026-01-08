package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyjenkins.GlobalVariable
import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovyjenkins.JenkinsWorkspaceManager
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.project.JenkinsCapabilities
import com.github.albertocavalcante.groovylsp.project.ProjectStrategyRegistry
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(WorkspaceManager::class.java)

    // Dependency classpath management
    private val dependencyClasspath = mutableListOf<Path>()
    private var workspaceRoot: Path? = null
    private val sourceRoots = mutableSetOf<Path>()
    private var workspaceSources: List<Path> = emptyList()

    // Jenkins workspace management (legacy - will be removed after migration)
    private var jenkinsWorkspaceManager: JenkinsWorkspaceManager? = null

    // Project strategy registry (new - replaces jenkinsWorkspaceManager)
    private var strategyRegistry: ProjectStrategyRegistry? = null

    /**
     * Sets the project strategy registry.
     * The registry manages project-type-specific strategies (Jenkins, Gradle, etc.).
     */
    fun setStrategyRegistry(registry: ProjectStrategyRegistry) {
        this.strategyRegistry = registry
        logger.info("Strategy registry set with {} strategies", registry.activeStrategies.size)
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
        logger.info("Initializing workspace (non-blocking): $workspaceRoot")
        this.workspaceRoot = workspaceRoot
        refreshSourceRoots(workspaceRoot)
        refreshWorkspaceSources()
    }

    fun updateDependencies(newDependencies: List<Path>): Boolean {
        var changed = false
        if (newDependencies.size != dependencyClasspath.size ||
            newDependencies.toSet() != dependencyClasspath.toSet()
        ) {
            dependencyClasspath.clear()
            dependencyClasspath.addAll(newDependencies)
            changed = true
            logger.info("Updated dependency classpath with ${dependencyClasspath.size} dependencies")
        } else {
            logger.debug("Dependencies unchanged")
        }
        return changed
    }

    fun updateWorkspaceModel(workspaceRoot: Path, dependencies: List<Path>, sourceDirectories: List<Path>): Boolean {
        this.workspaceRoot = workspaceRoot

        val depsChanged = dependencies.toSet() != dependencyClasspath.toSet()
        val sourcesChanged = sourceDirectories.toSet() != sourceRoots
        if (depsChanged) {
            dependencyClasspath.clear()
            dependencyClasspath.addAll(dependencies)
            logger.info("Updated dependency classpath with ${dependencyClasspath.size} dependencies")
        }

        if (sourceDirectories.isNotEmpty()) {
            sourceRoots.clear()
            sourceDirectories.forEach(sourceRoots::add)
            logger.info("Received ${sourceRoots.size} source roots from build model")
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
        } else {
            // Jenkins Shared Library structure: bare src/ directory
            // TODO(#701): Improve heuristics for detecting source roots in Light Mode
            //   See: https://github.com/albertocavalcante/gvy/issues/701
            val bareSrcDir = rootPath.resolve("src")
            if (Files.exists(bareSrcDir) && Files.isDirectory(bareSrcDir)) {
                sourceRoots.add(bareSrcDir)
            }
        }

        // Add source roots from active strategies (preferred)
        strategyRegistry?.activeStrategies?.forEach { strategy ->
            strategy.getSourceRoots().forEach { sourceRoot ->
                if (Files.exists(sourceRoot) && Files.isDirectory(sourceRoot)) {
                    sourceRoots.add(sourceRoot)
                    logger.debug("Added source root from strategy '{}': {}", strategy.id, sourceRoot)
                }
            }
        }

        // Add Jenkins library source roots if available (legacy fallback)
        if (strategyRegistry == null) {
            jenkinsWorkspaceManager?.getLibrarySourceRoots()?.forEach { librarySourceRoot ->
                if (Files.exists(librarySourceRoot) && Files.isDirectory(librarySourceRoot)) {
                    sourceRoots.add(librarySourceRoot)
                    logger.debug("Added Jenkins library source root: $librarySourceRoot")
                }
            }
        }

        logger.info("Indexed ${sourceRoots.size} source roots: ${sourceRoots.joinToString { it.toString() }}")
    }

    private fun refreshWorkspaceSources() {
        workspaceSources = sourceRoots.flatMap { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@flatMap emptyList<Path>()
            Files.walk(sourceRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.extension.equals("groovy", ignoreCase = true) }
                    .toList()
            }
        }

        logger.info("Indexed ${workspaceSources.size} Groovy sources from workspace roots")
        workspaceSources.forEach { logger.info("Indexed source: $it") }
    }

    fun getDependencyClasspath(): List<Path> = dependencyClasspath.toList()
    fun getWorkspaceRoot(): Path? = workspaceRoot
    fun getSourceRoots(): List<Path> = sourceRoots.toList()
    fun getWorkspaceSources(): List<Path> = workspaceSources

    /**
     * Gets workspace source URIs for indexing.
     * Converts Path objects to URI objects for use in compilation service.
     */
    fun getWorkspaceSourceUris(): List<URI> = workspaceSources.mapNotNull { path ->
        runCatching { path.toUri() }
            .getOrElse { throwable ->
                rethrowIfCancellationOrError(throwable)
                logger.warn("Failed to convert path to URI: $path", throwable)
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
     * Initializes Jenkins workspace manager with configuration.
     *
     * @deprecated Use [ProjectStrategyRegistry] with [JenkinsProjectStrategy] instead.
     */
    @Deprecated(
        message = "Use ProjectStrategyRegistry with JenkinsProjectStrategy instead",
        replaceWith = ReplaceWith("setStrategyRegistry(registry)"),
    )
    fun initializeJenkinsWorkspace(config: ServerConfiguration, pluginManager: JenkinsPluginManager? = null) {
        val root = workspaceRoot
        if (root != null) {
            val pm = pluginManager ?: JenkinsPluginManager()
            jenkinsWorkspaceManager = JenkinsWorkspaceManager(config.jenkinsConfig, root, pm)
            logger.info("Initialized Jenkins workspace manager")

            // Load GDSL metadata
            val gdslResults = jenkinsWorkspaceManager?.loadGdslMetadata()
            gdslResults?.let {
                logger.info("Loaded ${it.successful.size} Jenkins GDSL files, ${it.failed.size} failed")
            }
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
        // Try strategies first (preferred)
        strategyRegistry?.activeStrategies?.forEach { strategy ->
            val classpath = strategy.getClasspathForFile(uri, content, dependencyClasspath)
            if (classpath != null) {
                return classpath
            }
        }

        // Legacy fallback: Jenkins workspace manager
        if (strategyRegistry == null) {
            val jenkinsManager = jenkinsWorkspaceManager
            if (jenkinsManager != null && jenkinsManager.isJenkinsFile(uri)) {
                return jenkinsManager.getClasspathForFile(uri, content, dependencyClasspath)
            }
        }

        // Return standard dependency classpath for non-handled files
        return dependencyClasspath.toList()
    }

    /**
     * Checks if the given URI is a Jenkins pipeline file.
     *
     * @deprecated Use [getJenkinsCapabilities]`?.isJenkinsFile(uri)` instead.
     */
    @Deprecated(
        message = "Use getJenkinsCapabilities()?.isJenkinsFile(uri) instead",
        replaceWith = ReplaceWith("getJenkinsCapabilities()?.isJenkinsFile(uri) ?: false"),
    )
    fun isJenkinsFile(uri: URI): Boolean = getJenkinsCapabilities()?.isJenkinsFile(uri)
        ?: jenkinsWorkspaceManager?.isJenkinsFile(uri)
        ?: false

    /**
     * Checks if the given URI is a GDSL file.
     *
     * @deprecated Use [getJenkinsCapabilities]`?.isGdslFile(uri)` instead.
     */
    @Deprecated(
        message = "Use getJenkinsCapabilities()?.isGdslFile(uri) instead",
        replaceWith = ReplaceWith("getJenkinsCapabilities()?.isGdslFile(uri) ?: false"),
    )
    fun isGdslFile(uri: URI): Boolean = getJenkinsCapabilities()?.isGdslFile(uri)
        ?: jenkinsWorkspaceManager?.isGdslFile(uri)
        ?: false

    /**
     * Updates Jenkins configuration and rebuilds Jenkins context.
     *
     * @deprecated Use [JenkinsProjectStrategy.updateConfiguration] instead.
     */
    @Deprecated(
        message = "Use JenkinsProjectStrategy.updateConfiguration instead",
        replaceWith = ReplaceWith("strategyRegistry.findStrategy(\"jenkins\")?.updateConfiguration(config)"),
    )
    fun updateJenkinsConfiguration(config: ServerConfiguration) {
        // Update via strategy registry if available
        strategyRegistry?.findStrategy("jenkins")?.updateConfiguration(config)

        // Legacy fallback
        if (strategyRegistry == null) {
            val root = workspaceRoot
            if (root != null) {
                jenkinsWorkspaceManager = jenkinsWorkspaceManager?.updateConfiguration(config.jenkinsConfig)
                    ?: JenkinsWorkspaceManager(config.jenkinsConfig, root)
                logger.info("Updated Jenkins workspace configuration")
            }
        }
    }

    /**
     * Reloads GDSL metadata for Jenkins workspace.
     *
     * @deprecated Use [getJenkinsCapabilities]`?.reloadGdsl()` instead.
     */
    @Deprecated(
        message = "Use getJenkinsCapabilities()?.reloadGdsl() instead",
        replaceWith = ReplaceWith("getJenkinsCapabilities()?.reloadGdsl()"),
    )
    fun reloadJenkinsGdsl() {
        // Prefer strategy registry
        getJenkinsCapabilities()?.reloadGdsl()
            ?: jenkinsWorkspaceManager?.let { manager ->
                val results = manager.reloadGdslMetadata()
                logger.info("Reloaded ${results.successful.size} Jenkins GDSL files")
            }
    }

    /**
     * Gets global variables defined in Jenkins workspace (e.g. vars/ directory).
     *
     * @deprecated Use [getJenkinsCapabilities]`?.getGlobalVariables()` instead.
     */
    @Deprecated(
        message = "Use getJenkinsCapabilities()?.getGlobalVariables() instead",
        replaceWith = ReplaceWith("getJenkinsCapabilities()?.getGlobalVariables() ?: emptyList()"),
    )
    fun getJenkinsGlobalVariables(): List<GlobalVariable> = getJenkinsCapabilities()?.getGlobalVariables()
        ?: jenkinsWorkspaceManager?.getGlobalVariables()
        ?: emptyList()

    /**
     * Gets combined Jenkins metadata (steps, globals) including scanned plugins.
     *
     * @deprecated Use [getJenkinsCapabilities]`?.getAllMetadata()` instead.
     */
    @Deprecated(
        message = "Use getJenkinsCapabilities()?.getAllMetadata() instead",
        replaceWith = ReplaceWith("getJenkinsCapabilities()?.getAllMetadata()"),
    )
    fun getAllJenkinsMetadata() = getJenkinsCapabilities()?.getAllMetadata()
        ?: jenkinsWorkspaceManager?.getAllMetadata()
}
