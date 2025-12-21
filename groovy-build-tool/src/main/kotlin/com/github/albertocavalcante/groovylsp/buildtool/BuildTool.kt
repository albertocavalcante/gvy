package com.github.albertocavalcante.groovylsp.buildtool

import java.nio.file.Path

/**
 * interface representing a build tool (e.g. Gradle, Maven) capable of resolving dependencies.
 */
interface BuildTool {
    val name: String

    /**
     * Checks if this build tool can handle the given workspace.
     * @param workspaceRoot The root directory of the workspace.
     * @return true if this build tool detects a valid project it can manage.
     */
    fun canHandle(workspaceRoot: Path): Boolean

    /**
     * Resolves dependencies for the workspace.
     * @param workspaceRoot The root directory of the workspace.
     * @param onProgress Optional callback for progress updates.
     * @return The resolved workspace dependencies and source directories.
     */
    fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)? = null): WorkspaceResolution

    /**
     * Creates a file watcher for this build tool.
     * @param coroutineScope The scope to launch the watcher in.
     * @param onChange Callback triggered when a build file changes.
     * @return A watcher instance, or null if file watching is not supported.
     */
    fun createWatcher(
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        onChange: (Path) -> Unit,
    ): BuildToolFileWatcher? = null
}

/**
 * Marker interface for native Gradle Tooling API implementations.
 * Used for type-safe filtering in BuildToolManager without coupling to concrete classes.
 */
interface NativeGradleBuildTool : BuildTool

/**
 * Marker interface for BSP (Build Server Protocol) implementations.
 * Used for type-safe filtering in BuildToolManager without coupling to concrete classes.
 */
interface BspCompatibleBuildTool : BuildTool
