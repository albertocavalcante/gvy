package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovyjenkins.config.JenkinsPluginConfiguration
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadataLoader
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.updatecenter.JenkinsUpdateCenterClient
import com.github.albertocavalcante.groovylsp.buildtool.MavenSourceArtifactResolver
import com.github.albertocavalcante.groovylsp.buildtool.SourceArtifactResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Central orchestrator for Jenkins plugin metadata resolution.
 *
 * Implements a multi-tier fallback strategy:
 * 1. **Classpath**: Extract metadata from project dependencies (highest priority)
 * 2. **User Config**: Plugins specified in plugins.txt, resolved via Update Center
 * 3. **Downloaded**: Download and extract from Maven Central/Jenkins repo
 * 4. **Bundled**: Fall back to static bundled stubs (always available)
 *
 * This ensures the user always gets some level of support while enabling
 * rich, version-accurate metadata when dependencies are available.
 */
class JenkinsPluginManager(
    private val sourceResolver: SourceArtifactResolver = MavenSourceArtifactResolver(),
    private val metadataExtractor: JenkinsPluginMetadataExtractor = JenkinsPluginMetadataExtractor(),
    private val updateCenterClient: JenkinsUpdateCenterClient = JenkinsUpdateCenterClient(),
    private val pluginConfiguration: JenkinsPluginConfiguration = JenkinsPluginConfiguration(),
    private val cacheDir: Path = MavenSourceArtifactResolver.getDefaultCacheDir().parent.resolve("jenkins-plugins"),
) {

    private val logger = LoggerFactory.getLogger(JenkinsPluginManager::class.java)

    // Thread-safe lazy loading
    private val bundledMetadataLoader = BundledJenkinsMetadataLoader()
    private var bundledMetadata: BundledJenkinsMetadata? = null
    private val bundledMetadataMutex = Mutex()

    // Cache for extracted metadata from JARs
    private val extractedMetadataCache = mutableMapOf<String, List<JenkinsStepMetadata>>()
    private val cacheMutex = Mutex()

    // Cache for user-configured plugins (by workspace)
    private val userConfigCache = mutableMapOf<Path, List<JenkinsPluginConfiguration.PluginEntry>>()
    private val userConfigMutex = Mutex()

    // Cache for downloaded plugin JARs
    private val downloadedPluginCache = mutableMapOf<String, Path>()
    private val downloadedPluginMutex = Mutex()

    /**
     * Resolve metadata for a Jenkins step using multi-tier fallback.
     *
     * @param stepName Name of the Jenkins step (e.g., "sh", "withCredentials")
     * @param classpathJars JARs from the project's resolved classpath
     * @param workspaceRoot Optional workspace root for loading user config
     * @return Step metadata or null if not found at any tier
     */
    suspend fun resolveStepMetadata(
        stepName: String,
        classpathJars: List<Path> = emptyList(),
        workspaceRoot: Path? = null,
    ): JenkinsStepMetadata? {
        logger.debug("Resolving step metadata for: {}", stepName)

        // Tier 1: Check classpath JARs
        val classpathMetadata = findInClasspath(stepName, classpathJars)
        if (classpathMetadata != null) {
            logger.debug("Found {} in classpath", stepName)
            return classpathMetadata
        }

        // Tier 2: Check user-configured plugins
        if (workspaceRoot != null) {
            val userConfigMetadata = findInUserConfig(stepName, workspaceRoot)
            if (userConfigMetadata != null) {
                logger.debug("Found {} in user-configured plugins", stepName)
                return userConfigMetadata
            }
        }

        // Tier 3: Check downloaded plugins (from previous resolutions)
        val downloadedMetadata = findInDownloadedPlugins(stepName)
        if (downloadedMetadata != null) {
            logger.debug("Found {} in downloaded plugins", stepName)
            return downloadedMetadata
        }

        // Tier 4: Fall back to bundled stubs
        val bundled = getBundledMetadata()
        val bundledStep = bundled.getStep(stepName)
        if (bundledStep != null) {
            logger.debug("Found {} in bundled stubs", stepName)
            return bundledStep
        }

        logger.debug("Step {} not found in any tier", stepName)
        return null
    }

    /**
     * Search for step metadata in user-configured plugins.
     */
    private suspend fun findInUserConfig(stepName: String, workspaceRoot: Path): JenkinsStepMetadata? {
        val plugins = loadUserPlugins(workspaceRoot)

        for (plugin in plugins) {
            val coords = plugin.toMavenCoordinates()
            if (coords != null) {
                val jarPath = resolvePluginJar(coords)
                if (jarPath != null) {
                    val extracted = extractMetadataFromJar(jarPath)
                    val step = extracted.find { it.name == stepName }
                    if (step != null) return step
                }
            }
        }
        return null
    }

    /**
     * Load user-configured plugins from workspace, with caching.
     */
    private suspend fun loadUserPlugins(workspaceRoot: Path): List<JenkinsPluginConfiguration.PluginEntry> =
        userConfigMutex.withLock {
            userConfigCache.getOrPut(workspaceRoot) {
                pluginConfiguration.loadPluginsFromWorkspace(workspaceRoot)
            }
        }

    /**
     * Resolve a plugin JAR from Maven coordinates.
     */
    private suspend fun resolvePluginJar(coords: JenkinsPluginConfiguration.MavenCoordinates): Path? {
        val key = "${coords.groupId}:${coords.artifactId}:${coords.version}"

        // Check cache first
        val cached = downloadedPluginMutex.withLock { downloadedPluginCache[key] }
        if (cached != null && Files.exists(cached)) {
            return cached
        }

        // Try to resolve via source resolver
        return try {
            val jarPath = sourceResolver.resolveSourceJar(coords.groupId, coords.artifactId, coords.version)
            if (jarPath != null) {
                downloadedPluginMutex.withLock {
                    downloadedPluginCache[key] = jarPath
                }
            }
            jarPath
        } catch (e: Exception) {
            logger.debug("Failed to resolve plugin JAR: {}", key, e)
            null
        }
    }

    /**
     * Search for step metadata in downloaded plugins.
     */
    private suspend fun findInDownloadedPlugins(stepName: String): JenkinsStepMetadata? {
        val downloadedJars = downloadedPluginMutex.withLock {
            downloadedPluginCache.values.toList()
        }

        for (jarPath in downloadedJars) {
            if (Files.exists(jarPath)) {
                val extracted = extractMetadataFromJar(jarPath)
                val step = extracted.find { it.name == stepName }
                if (step != null) return step
            }
        }
        return null
    }

    /**
     * Get all available step names from all tiers.
     */
    suspend fun getAllKnownSteps(classpathJars: List<Path> = emptyList()): Set<String> {
        val steps = mutableSetOf<String>()

        // Add bundled steps
        val bundled = getBundledMetadata()
        steps.addAll(bundled.steps.keys)

        // Add classpath steps
        classpathJars.forEach { jar ->
            val extracted = extractMetadataFromJar(jar)
            steps.addAll(extracted.map { it.name })
        }

        return steps
    }

    /**
     * Search for step metadata in classpath JARs.
     */
    private suspend fun findInClasspath(stepName: String, classpathJars: List<Path>): JenkinsStepMetadata? {
        for (jar in classpathJars) {
            val extracted = extractMetadataFromJar(jar)
            val step = extracted.find { it.name == stepName }
            if (step != null) return step
        }
        return null
    }

    /**
     * Extract metadata from a JAR, with caching.
     */
    private suspend fun extractMetadataFromJar(jarPath: Path): List<JenkinsStepMetadata> {
        val key = jarPath.toString()

        val cached = cacheMutex.withLock {
            extractedMetadataCache[key]
        }
        if (cached != null) return cached

        // Check if this looks like a Jenkins plugin
        val fileName = jarPath.fileName.toString()
        if (!isLikelyJenkinsPlugin(fileName)) {
            return emptyList()
        }

        val extracted = metadataExtractor.extractFromJar(jarPath, fileName.removeSuffix(".jar"))

        cacheMutex.withLock {
            extractedMetadataCache[key] = extracted
        }

        return extracted
    }

    /**
     * Heuristic to identify likely Jenkins plugins.
     */
    private fun isLikelyJenkinsPlugin(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return lowerName.contains("workflow") ||
            lowerName.contains("jenkins") ||
            lowerName.contains("pipeline") ||
            lowerName.contains("plugin") ||
            lowerName.contains("credentials") ||
            lowerName.contains("scm")
    }

    /**
     * Get bundled metadata, loading lazily if needed.
     */
    private suspend fun getBundledMetadata(): BundledJenkinsMetadata = bundledMetadataMutex.withLock {
        if (bundledMetadata == null) {
            bundledMetadata = try {
                bundledMetadataLoader.load()
            } catch (e: Exception) {
                logger.error("Failed to load bundled Jenkins metadata", e)
                // Return empty metadata on failure
                BundledJenkinsMetadata(emptyMap(), emptyMap())
            }
        }
        bundledMetadata!!
    }

    /**
     * Force refresh of cached metadata.
     */
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            extractedMetadataCache.clear()
        }
        bundledMetadataMutex.withLock {
            bundledMetadata = null
        }
        logger.info("Invalidated Jenkins plugin manager cache")
    }
}
