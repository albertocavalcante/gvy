package com.github.albertocavalcante.groovylsp.buildtool

import com.github.albertocavalcante.groovylsp.buildtool.maven.AetherSessionFactory
import com.github.albertocavalcante.groovylsp.buildtool.maven.DefaultRepositoryProvider
import com.github.albertocavalcante.groovylsp.buildtool.maven.RepositoryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maven-based implementation of SourceArtifactResolver using Eclipse Aether.
 *
 * Downloads and caches source JARs from configured Maven repositories.
 * Unlike the previous implementation, this version:
 * - Uses shared AetherSessionFactory (no code duplication)
 * - Accepts repositories from the project's build configuration
 * - Supports Nexus/Artifactory when configured in pom.xml
 */
class MavenSourceArtifactResolver(
    override val cacheDir: Path = AetherSessionFactory.getCacheDirectory("sources"),
    private val repositoryProvider: RepositoryProvider = DefaultRepositoryProvider(),
) : SourceArtifactResolver {

    private val logger = LoggerFactory.getLogger(MavenSourceArtifactResolver::class.java)

    override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
        resolveSourceJar(groupId, artifactId, version, repositoryProvider.getRepositories())

    /**
     * Resolve source JAR with explicit repository list.
     *
     * This overload allows callers to provide project-specific repositories,
     * enabling resolution from Nexus/Artifactory or other custom registries.
     */
    suspend fun resolveSourceJar(
        groupId: String,
        artifactId: String,
        version: String,
        repositories: List<RemoteRepository>,
    ): Path? = withContext(Dispatchers.IO) {
        logger.debug("Resolving sources JAR: {}:{}:{}", groupId, artifactId, version)

        // Check cache first
        val cachedPath = getCachePath(groupId, artifactId, version)
        if (Files.exists(cachedPath)) {
            logger.debug("Found cached sources JAR: {}", cachedPath)
            return@withContext cachedPath
        }

        try {
            val session = AetherSessionFactory.createSession(cacheDir)
            val artifact = DefaultArtifact(groupId, artifactId, "sources", "jar", version)
            val request = ArtifactRequest().apply {
                this.artifact = artifact
                this.repositories = repositories
            }

            val result = AetherSessionFactory.repositorySystem.resolveArtifact(session, request)

            if (result.isResolved) {
                val resolvedPath = result.artifact.file.toPath()
                logger.info("Resolved sources JAR: {}", resolvedPath)

                // Copy to our cache directory for consistent access
                ensureCacheDirectory(groupId, artifactId, version)
                if (resolvedPath != cachedPath && Files.notExists(cachedPath)) {
                    Files.copy(resolvedPath, cachedPath)
                }
                cachedPath
            } else {
                logger.warn("Sources JAR not found: {}:{}:{}", groupId, artifactId, version)
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to resolve sources JAR: {}:{}:{}", groupId, artifactId, version, e)
            null
        }
    }

    override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean {
        val cachePath = getCachePath(groupId, artifactId, version)
        return Files.exists(cachePath)
    }

    /**
     * Build the cache path following Maven repository layout.
     */
    private fun getCachePath(groupId: String, artifactId: String, version: String): Path {
        val groupPath = groupId.replace('.', '/')
        val fileName = "$artifactId-$version-sources.jar"
        return cacheDir.resolve(groupPath).resolve(artifactId).resolve(version).resolve(fileName)
    }

    /**
     * Ensure the cache directory structure exists.
     */
    private fun ensureCacheDirectory(groupId: String, artifactId: String, version: String) {
        val cachePath = getCachePath(groupId, artifactId, version)
        val parentDir = cachePath.parent
        if (parentDir != null && Files.notExists(parentDir)) {
            Files.createDirectories(parentDir)
        }
    }

    companion object {
        /**
         * Default cache directory: ~/.groovy-lsp/cache/sources
         */
        fun getDefaultCacheDir(): Path = AetherSessionFactory.getCacheDirectory("sources")
    }
}
