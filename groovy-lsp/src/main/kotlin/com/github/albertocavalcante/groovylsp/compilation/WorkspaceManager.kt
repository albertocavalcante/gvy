package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyjenkins.JenkinsWorkspaceManager
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
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

    // Jenkins workspace management
    private var jenkinsWorkspaceManager: JenkinsWorkspaceManager? = null

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
            val bareSrcDir = rootPath.resolve("src")
            if (Files.exists(bareSrcDir) && Files.isDirectory(bareSrcDir)) {
                sourceRoots.add(bareSrcDir)
            }
        }

        // Add Jenkins library source roots if available
        jenkinsWorkspaceManager?.getLibrarySourceRoots()?.forEach { librarySourceRoot ->
            if (Files.exists(librarySourceRoot) && Files.isDirectory(librarySourceRoot)) {
                sourceRoots.add(librarySourceRoot)
                logger.debug("Added Jenkins library source root: $librarySourceRoot")
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
        try {
            path.toUri()
        } catch (e: Exception) {
            logger.warn("Failed to convert path to URI: $path", e)
            null
        }
    }

    /**
     * Initializes Jenkins workspace manager with configuration.
     */

    /**
     * Initializes Jenkins workspace manager with configuration.
     */
    fun initializeJenkinsWorkspace(
        config: ServerConfiguration,
        pluginManager: com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager? = null,
    ) {
        val root = workspaceRoot
        if (root != null) {
            val pm = pluginManager ?: com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager()
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
     * Gets the classpath for a file, including Jenkins-specific classpath if applicable.
     */
    fun getClasspathForFile(uri: URI, content: String): List<Path> {
        val jenkinsManager = jenkinsWorkspaceManager
        if (jenkinsManager != null && jenkinsManager.isJenkinsFile(uri)) {
            // Return Jenkins-specific classpath, but ALSO include project dependencies
            return jenkinsManager.getClasspathForFile(uri, content, dependencyClasspath)
        }
        // Return standard dependency classpath for non-Jenkins files
        return dependencyClasspath.toList()
    }

    /**
     * Checks if the given URI is a Jenkins pipeline file.
     */
    fun isJenkinsFile(uri: URI): Boolean = jenkinsWorkspaceManager?.isJenkinsFile(uri) ?: false

    /**
     * Checks if the given URI is a GDSL file.
     */
    fun isGdslFile(uri: URI): Boolean = jenkinsWorkspaceManager?.isGdslFile(uri) ?: false

    /**
     * Updates Jenkins configuration and rebuilds Jenkins context.
     */
    fun updateJenkinsConfiguration(config: ServerConfiguration) {
        val root = workspaceRoot
        if (root != null) {
            // If manager exists, update it (preserves pluginManager inside)
            jenkinsWorkspaceManager = jenkinsWorkspaceManager?.updateConfiguration(config.jenkinsConfig)
                ?: JenkinsWorkspaceManager(config.jenkinsConfig, root)
            logger.info("Updated Jenkins workspace configuration")
        }
    }

    /**
     * Reloads GDSL metadata for Jenkins workspace.
     */
    fun reloadJenkinsGdsl() {
        jenkinsWorkspaceManager?.let { manager ->
            val results = manager.reloadGdslMetadata()
            logger.info("Reloaded ${results.successful.size} Jenkins GDSL files")
        }
    }

    /**
     * Gets global variables defined in Jenkins workspace (e.g. vars/ directory).
     */
    fun getJenkinsGlobalVariables(): List<com.github.albertocavalcante.groovyjenkins.GlobalVariable> =
        jenkinsWorkspaceManager?.getGlobalVariables() ?: emptyList()

    /**
     * Gets combined Jenkins metadata (steps, globals) including scanned plugins.
     */
    fun getAllJenkinsMetadata() = jenkinsWorkspaceManager?.getAllMetadata()
}
