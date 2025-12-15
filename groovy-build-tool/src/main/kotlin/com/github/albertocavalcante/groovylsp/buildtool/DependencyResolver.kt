package com.github.albertocavalcante.groovylsp.buildtool

import java.nio.file.Path

/**
 * Common interface for programmatic dependency resolvers.
 *
 * This abstraction allows different build tools (Maven, Gradle, Bazel, etc.)
 * to implement their own resolution logic while maintaining a consistent API.
 */
interface DependencyResolver {
    /**
     * Human-readable name of the resolver.
     */
    val name: String

    /**
     * Resolves dependencies programmatically from a project descriptor file.
     *
     * @param projectFile Path to the project file (e.g., pom.xml, build.gradle)
     * @return List of resolved dependency JAR paths, empty list on failure
     */
    fun resolveDependencies(projectFile: Path): List<Path>

    /**
     * Optional: Resolves the local artifact repository path.
     * This is useful for finding cached artifacts like jenkins-core.
     *
     * @return Path to the local repository, or null if not applicable
     */
    fun resolveLocalRepository(): Path? = null
}
