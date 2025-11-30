package com.github.albertocavalcante.groovylsp.gradle

import java.nio.file.Path

data class WorkspaceResolution(val dependencies: List<Path>, val sourceDirectories: List<Path>)

/**
 * Interface for resolving project dependencies.
 *
 * Implementations should accept an optional onDownloadProgress callback for progress updates.
 */
fun interface DependencyResolver {
    fun resolve(projectDir: Path, onDownloadProgress: ((String) -> Unit)?): WorkspaceResolution
}
