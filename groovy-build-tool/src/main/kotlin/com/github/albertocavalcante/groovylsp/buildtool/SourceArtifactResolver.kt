package com.github.albertocavalcante.groovylsp.buildtool

import java.nio.file.Path

/**
 * Interface for resolving source JARs for Maven artifacts.
 *
 * This supports features like:
 * - Go-to-definition for classes in dependency JARs
 * - Extracting JavaDoc for hover documentation
 * - Jenkins plugin source inspection
 */
interface SourceArtifactResolver {

    /**
     * Resolve and download the sources JAR for a given Maven artifact.
     *
     * @param groupId Maven groupId (e.g., "org.jenkins-ci.plugins.workflow")
     * @param artifactId Maven artifactId (e.g., "workflow-step-api")
     * @param version Artifact version (e.g., "2.24")
     * @return Path to the downloaded sources JAR, or null if unavailable
     */
    suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path?

    /**
     * Check if sources for an artifact are already cached locally.
     *
     * @return true if sources are cached and available locally
     */
    fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean

    /**
     * Get the cache directory path for sources.
     */
    val cacheDir: Path
}
