package com.github.albertocavalcante.groovylsp.buildtool

import java.nio.file.Path

/**
 * Interface for watching build files for changes.
 */
interface BuildToolFileWatcher {
    /**
     * Starts watching for build file changes in the given project directory.
     * @param projectDir The root directory of the project to watch.
     */
    fun startWatching(projectDir: Path)

    /**
     * Stops watching for changes.
     */
    fun stopWatching()

    /**
     * Checks if the watcher is currently active.
     */
    fun isWatching(): Boolean
}
