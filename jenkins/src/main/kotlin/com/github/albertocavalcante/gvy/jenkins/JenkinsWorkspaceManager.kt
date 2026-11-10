@file:Suppress("TooGenericExceptionCaught") // Path/URI parsing uses catch-all for resilience

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.gdsl.GdslLoadResults
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Jenkins workspace context separately from regular Groovy sources.
 * Provides Jenkins-specific classpath and prevents symbol leakage.
 */
class JenkinsWorkspaceManager internal constructor(
    private val configuration: JenkinsConfiguration,
    private val workspaceRoot: Path,
    private val pluginManager: JenkinsPluginManager,
    private val stubsCacheRoot: Path?,
    private val jenkinsContext: JenkinsContext,
) {
    constructor(
        configuration: JenkinsConfiguration,
        workspaceRoot: Path,
        pluginManager: JenkinsPluginManager = JenkinsPluginManager(),
        stubsCacheRoot: Path? = null,
    ) : this(
        configuration,
        workspaceRoot,
        pluginManager,
        stubsCacheRoot,
        JenkinsContext(configuration, workspaceRoot, pluginManager, stubsCacheRoot),
    )

    private val logger = KotlinLogging.logger {}
    private val varsProvider = VarsGlobalVariableProvider(workspaceRoot)
    private val librarySourceLoader = LibrarySourceLoader()

    // Cache for parsed library references to avoid redundant AST parsing
    private data class CacheEntry(val contentHash: Int, val libraries: List<LibraryReference>)

    private val libraryCache = ConcurrentHashMap<URI, CacheEntry>()

    /** The scan key of the classpath each Jenkinsfile was last compiled with, for [getAllMetadata]; LRU-bounded. */
    private val classpathByUri = object : LinkedHashMap<URI, List<Path>>(INITIAL_URI_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<URI, List<Path>>) = size > MAX_TRACKED_FILES
    }

    /**
     * Checks if the given URI represents a Jenkins pipeline file.
     */
    fun isJenkinsFile(uri: URI): Boolean = jenkinsContext.isJenkinsFile(uri)

    /**
     * Checks if the given URI represents a GDSL file based on configuration.
     */
    fun isGdslFile(uri: URI): Boolean {
        val path = try {
            java.nio.file.Paths.get(uri)
        } catch (e: Exception) {
            logger.debug(e) { "Could not convert URI to path: $uri" }
            return false
        }

        // Simple check: if it ends with .gdsl, it's likely a GDSL file
        // Also check against configured patterns
        if (path.toString().endsWith(".gdsl")) {
            return true
        }

        return configuration.gdslPaths.any { pattern ->
            try {
                val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
                matcher.matches(path) || matcher.matches(path.fileName)
            } catch (e: Exception) {
                logger.debug(e) { "Invalid glob pattern: $pattern" }
                false
            }
        }
    }

    /**
     * Gets the classpath for a specific file.
     * Returns Jenkins classpath if it's a Jenkinsfile, empty otherwise.
     * Uses caching to avoid redundant AST parsing when content hasn't changed.
     */
    fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path> = emptyList()): List<Path> {
        if (!isJenkinsFile(uri)) {
            return emptyList()
        }

        // Check cache first - only re-parse if content changed
        val contentHash = content.hashCode()
        val cached = libraryCache[uri]
        val libraries = if (cached != null && cached.contentHash == contentHash) {
            logger.debug { "Using cached library references for $uri" }
            cached.libraries
        } else {
            // Parse library references from the Jenkinsfile
            val parsed = jenkinsContext.parseLibraries(content)
            libraryCache[uri] = CacheEntry(contentHash, parsed)
            parsed
        }

        // Build classpath from references AND project dependencies
        val scoped = jenkinsContext.buildScopedClasspath(libraries, projectDependencies)
        synchronized(classpathByUri) { classpathByUri[uri] = scoped.scanKey }
        val classpath = scoped.entries

        // Extract library sources for navigation
        extractLibrarySources(libraries)

        logger.debug { "Built Jenkins classpath for $uri: ${classpath.size} entries" }
        return classpath
    }

    /**
     * Extracts source files from configured shared libraries.
     */
    private fun extractLibrarySources(libraryReferences: List<LibraryReference>) {
        val resolver = SharedLibraryResolver(configuration)
        val resolvedLibraries = resolver.resolveAll(libraryReferences)

        resolvedLibraries.forEach { library ->
            librarySourceLoader.extractSources(library)
        }
    }

    /**
     * Gets source root directories for extracted library sources.
     * These can be added to compilation source roots for navigation.
     */
    fun getLibrarySourceRoots(): List<Path> {
        // Extract sources for all configured libraries if not already extracted
        configuration.sharedLibraries.forEach { library ->
            librarySourceLoader.extractSources(library)
        }
        return librarySourceLoader.getExtractedSourceRoots()
    }

    /**
     * Loads GDSL metadata for Jenkins context.
     */
    fun loadGdslMetadata(): GdslLoadResults = jenkinsContext.loadGdslMetadata()

    /**
     * Reloads GDSL metadata; the next [getAllMetadata] reflects the files' current contents.
     */
    fun reloadGdslMetadata(): GdslLoadResults {
        logger.info { "Reloading Jenkins GDSL metadata" }
        return loadGdslMetadata()
    }

    /**
     * Updates configuration and rebuilds Jenkins context.
     */
    fun updateConfiguration(newConfig: JenkinsConfiguration): JenkinsWorkspaceManager {
        logger.info { "Updating Jenkins configuration" }
        return JenkinsWorkspaceManager(newConfig, workspaceRoot, pluginManager, stubsCacheRoot)
    }

    /**
     * Gets global variables defined in the workspace (e.g. vars/ directory).
     */
    fun getGlobalVariables(): List<GlobalVariable> = varsProvider.getGlobalVariables()

    /**
     * Get combined metadata (bundled + dynamic) from context.
     */
    fun getAllMetadata() = jenkinsContext.getAllMetadata()

    /**
     * Metadata for the Jenkinsfile [uri]: bundled and GDSL metadata plus the library steps scanned from the
     * classpath it was last compiled with, never another file's (#1242). Before its first compile (or after
     * its classpath was evicted) it has no scanned steps.
     */
    fun getAllMetadata(uri: URI) = jenkinsContext.getAllMetadata(synchronized(classpathByUri) { classpathByUri[uri] })

    private companion object {
        /** Jenkinsfiles whose classpath is remembered; each entry is a short list of paths. */
        const val MAX_TRACKED_FILES = 256
        const val INITIAL_URI_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
