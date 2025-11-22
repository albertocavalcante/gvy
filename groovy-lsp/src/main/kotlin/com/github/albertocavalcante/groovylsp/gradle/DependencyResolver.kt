package com.github.albertocavalcante.groovylsp.gradle

import java.nio.file.Path

data class WorkspaceResolution(val dependencies: List<Path>, val sourceDirectories: List<Path>)

fun interface DependencyResolver {
    fun resolve(projectDir: Path): WorkspaceResolution
}
