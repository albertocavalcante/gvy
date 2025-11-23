package com.github.albertocavalcante.groovylsp.compilation

import org.slf4j.LoggerFactory
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
class WorkspaceManager {
    private val logger = LoggerFactory.getLogger(WorkspaceManager::class.java)

    // Dependency classpath management
    private val dependencyClasspath = mutableListOf<Path>()
    private var workspaceRoot: Path? = null
    private val sourceRoots = mutableSetOf<Path>()
    private var workspaceSources: List<Path> = emptyList()

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

    private fun refreshSourceRoots(root: Path) {
        if (sourceRoots.isEmpty()) {
            val candidates = listOf(
                root.resolve("src/main/groovy"),
                root.resolve("src/main/java"),
                root.resolve("src/main/kotlin"),
                root.resolve("src/test/groovy"),
            )

            candidates.filter { Files.exists(it) && it.isDirectory() }.forEach(sourceRoots::add)

            logger.info("Indexed ${sourceRoots.size} source roots: ${sourceRoots.joinToString { it.toString() }}")
        }
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
    }

    fun getDependencyClasspath(): List<Path> = dependencyClasspath.toList()
    fun getWorkspaceRoot(): Path? = workspaceRoot
    fun getSourceRoots(): List<Path> = sourceRoots.toList()
    fun getWorkspaceSources(): List<Path> = workspaceSources
}
