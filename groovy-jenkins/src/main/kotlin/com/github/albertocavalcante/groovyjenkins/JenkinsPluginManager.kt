@file:Suppress(
    "TooGenericExceptionCaught", // Plugin resolution uses catch-all for resilience
    "ReturnCount", // Multiple early returns are clearer in search methods
    "NestedBlockDepth", // Plugin resolution has inherent nesting
    "LoopWithTooManyJumpStatements", // Iterating plugins with multiple guard clauses (continue)
)

package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovyjenkins.config.JenkinsPluginConfiguration
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadataLoader
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MetadataMerger
import com.github.albertocavalcante.groovyjenkins.metadata.StableStepDefinitions
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
 * Implements a functional pipeline strategy where sources are folded:
 * 1. **Bundled**: Base fallback (static stubs)
 * 2. **Stable**: Hardcoded core definitions
 * 3. **Static Metadata**: Loaded from JSON file (Configurable)
 * 4. **User Config & Downloaded**: Plugins specified in plugins.txt or downloaded
 * 5. **Classpath**: Project dependencies (Highest priority)
 */
@Suppress("UnusedPrivateProperty") // TODO: updateCenterClient and cacheDir reserved for future use
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
    private val bundledMetadataMutex = Mutex()
    private var bundledMetadataCache: BundledJenkinsMetadata? = null

    // Cache for extracted metadata from JARs (Jar Path -> Map<StepName, Step>)
    private val extractedMetadataCache = mutableMapOf<String, Map<String, JenkinsStepMetadata>>()
    private val cacheMutex = Mutex()

    // Cache for user-configured plugins (by workspace)
    private val userConfigCache = mutableMapOf<Path, List<JenkinsPluginConfiguration.PluginEntry>>()
    private val userConfigMutex = Mutex()

    // Cache for downloaded plugin JARs
    private val downloadedPluginCache = mutableMapOf<String, Path>()
    private val downloadedPluginMutex = Mutex()

    // Cache for registered static metadata
    private var staticMetadataCache: BundledJenkinsMetadata? = null
    private val staticMetadataMutex = Mutex()

    /**
     * Manually register a plugin JAR (e.g. from startup downloader).
     */
    suspend fun registerPluginJar(pluginId: String, jarPath: Path) {
        downloadedPluginMutex.withLock {
            downloadedPluginCache[pluginId] = jarPath
        }
        logger.debug("Registered plugin JAR for {}: {}", pluginId, jarPath)
    }

    /**
     * Get all registered/downloaded plugin JARs.
     */
    suspend fun getRegisteredPluginJars(): List<Path> = downloadedPluginMutex.withLock {
        downloadedPluginCache.values.toList()
    }

    /**
     * Register static metadata (e.g. from JSON file).
     */
    suspend fun registerStaticMetadata(metadata: BundledJenkinsMetadata) {
        staticMetadataMutex.withLock {
            // Merge with existing if any, or just set it
            if (staticMetadataCache == null) {
                staticMetadataCache = metadata
            } else {
                // Simplistic merge: overwrite (since we expect one main file)
                // Or we could implement deep merging logic here if needed.
                // For now, let's treat it as "last writer wins" or just replacement.
                staticMetadataCache = metadata
            }
        }
        logger.debug("Registered static Jenkins metadata with {} steps", metadata.steps.size)
    }

    /**
     * Resolve metadata for a Jenkins step using functional pipeline.
     */
    suspend fun resolveStepMetadata(
        stepName: String,
        classpathJars: List<Path> = emptyList(),
        workspaceRoot: Path? = null,
    ): JenkinsStepMetadata? {
        // Define sources from lowest to highest priority
        // Collect candidate steps from each tier (ordered by priority: Lowest -> Highest)
        val candidates = mutableListOf<JenkinsStepMetadata>()

        // 1. Bundled (Lowest)
        getBundledMetadata().getStep(stepName)?.let { candidates.add(it) }

        // 2. Stable Definitions
        StableStepDefinitions.all()[stepName]?.let { candidates.add(it) }

        // 3. Static Metadata (Medium-Low)
        getStaticMetadata()?.getStep(stepName)?.let { candidates.add(it) }

        // 4. User Config & Downloaded (Medium)
        // Check user config first
        if (workspaceRoot != null) {
            findStepInUserConfig(stepName, workspaceRoot)?.let { candidates.add(it) }
        }
        // Then downloaded plugins (fallback heuristic)
        findStepInDownloadedPlugins(stepName)?.let { candidates.add(it) }

        // 5. Classpath (Highest)
        findStepInClasspath(stepName, classpathJars)?.let { candidates.add(it) }

        if (candidates.isEmpty()) return null

        // Reduce using the Semigroup to merge parameters deeply
        // acc = lower priority, next = higher priority. 'combine' ensures next overrides acc where appropriate.
        return candidates.reduce { acc, next ->
            MetadataMerger.mergeStepParameters(acc, next)
        }
    }

    // --- Helper lookup methods (Lazy / Specific) ---

    private suspend fun findStepInClasspath(stepName: String, classpathJars: List<Path>): JenkinsStepMetadata? =
        classpathJars.firstNotNullOfOrNull { jar ->
            getMetadataFromJar(jar)[stepName]
        }

    private suspend fun findStepInUserConfig(stepName: String, workspaceRoot: Path): JenkinsStepMetadata? =
        loadUserPlugins(workspaceRoot).firstNotNullOfOrNull { plugin ->
            plugin.toMavenCoordinates()?.let { coords ->
                resolvePluginJar(coords)?.let { jarPath ->
                    getMetadataFromJar(jarPath)[stepName]
                }
            }
        }

    private suspend fun findStepInDownloadedPlugins(stepName: String): JenkinsStepMetadata? {
        val downloadedJars = downloadedPluginMutex.withLock {
            downloadedPluginCache.values.toList()
        }
        return downloadedJars.firstNotNullOfOrNull { jarPath ->
            if (Files.exists(jarPath)) {
                getMetadataFromJar(jarPath)[stepName]
            } else {
                null
            }
        }
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
     * Get all available step names from all tiers.
     */
    suspend fun getAllKnownSteps(classpathJars: List<Path> = emptyList()): Set<String> {
        val names = mutableSetOf<String>()
        names.addAll(getBundledMetadata().steps.keys)
        names.addAll(StableStepDefinitions.all().keys)
        getStaticMetadata()?.let { names.addAll(it.steps.keys) }

        classpathJars.forEach { jar ->
            names.addAll(getMetadataFromJar(jar).keys)
        }
        return names
    }

    /**
     * EXTRACT metadata from a JAR (or retrieve from cache), returning a Name->Step Map.
     * Renamed to getMetadataFromJar to reflect retrieval + caching.
     */
    private suspend fun getMetadataFromJar(jarPath: Path): Map<String, JenkinsStepMetadata> {
        val key = jarPath.toString()

        val cached = cacheMutex.withLock {
            extractedMetadataCache[key]
        }
        if (cached != null) return cached

        // Check if this looks like a Jenkins plugin
        val fileName = jarPath.fileName.toString()
        if (!isLikelyJenkinsPlugin(fileName)) {
            return emptyMap()
        }

        val extractedList = metadataExtractor.extractFromJar(jarPath, fileName.removeSuffix(".jar"))
        val extractedMap = extractedList.associateBy { it.name }

        cacheMutex.withLock {
            extractedMetadataCache[key] = extractedMap
        }

        return extractedMap
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
        if (bundledMetadataCache == null) {
            bundledMetadataCache = try {
                bundledMetadataLoader.load()
            } catch (e: Exception) {
                logger.error("Failed to load bundled Jenkins metadata", e)
                // Return empty metadata on failure
                BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap())
            }
        }
        bundledMetadataCache!!
    }

    /**
     * Get static metadata, safely.
     */
    private suspend fun getStaticMetadata(): BundledJenkinsMetadata? = staticMetadataMutex.withLock {
        staticMetadataCache
    }

    /**
     * Force refresh of cached metadata.
     */
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            extractedMetadataCache.clear()
        }
        bundledMetadataMutex.withLock {
            bundledMetadataCache = null
        }
        staticMetadataMutex.withLock {
            staticMetadataCache = null
        }
        logger.info("Invalidated Jenkins plugin manager cache")
    }
}
