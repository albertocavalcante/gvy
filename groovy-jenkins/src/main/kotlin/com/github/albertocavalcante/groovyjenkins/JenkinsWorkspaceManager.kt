@file:Suppress("TooGenericExceptionCaught") // Path/URI parsing uses catch-all for resilience

package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovygdsl.GdslLoadResults
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Jenkins workspace context separately from regular Groovy sources.
 * Provides Jenkins-specific classpath and prevents symbol leakage.
 */
class JenkinsWorkspaceManager(
    private val configuration: JenkinsConfiguration,
    private val workspaceRoot: Path,
    private val pluginManager: JenkinsPluginManager = JenkinsPluginManager(),
) {
    private val logger = LoggerFactory.getLogger(JenkinsWorkspaceManager::class.java)
    private val jenkinsContext = JenkinsContext(configuration, workspaceRoot, pluginManager)
    private val varsProvider = VarsGlobalVariableProvider(workspaceRoot)
    private val librarySourceLoader = LibrarySourceLoader()

    // Cache for parsed library references to avoid redundant AST parsing
    private data class CacheEntry(val contentHash: Int, val libraries: List<LibraryReference>)

    private val libraryCache = ConcurrentHashMap<URI, CacheEntry>()

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
            logger.debug("Could not convert URI to path: $uri", e)
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
                logger.debug("Invalid glob pattern: $pattern", e)
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
            logger.debug("Using cached library references for $uri")
            cached.libraries
        } else {
            // Parse library references from the Jenkinsfile
            val parsed = jenkinsContext.parseLibraries(content)
            libraryCache[uri] = CacheEntry(contentHash, parsed)
            parsed
        }

        // Build classpath from references AND project dependencies
        val classpath = jenkinsContext.buildClasspath(libraries, projectDependencies)

        // Extract library sources for navigation
        extractLibrarySources(libraries)

        logger.debug("Built Jenkins classpath for $uri: ${classpath.size} entries")
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
     * Reloads GDSL metadata.
     */
    fun reloadGdslMetadata(): GdslLoadResults {
        logger.info("Reloading Jenkins GDSL metadata")
        // In a real implementation, this would likely clear caches or update symbol tables
        return loadGdslMetadata()
    }

    /**
     * Updates configuration and rebuilds Jenkins context.
     */
    fun updateConfiguration(newConfig: JenkinsConfiguration): JenkinsWorkspaceManager {
        logger.info("Updating Jenkins configuration")
        return JenkinsWorkspaceManager(newConfig, workspaceRoot, pluginManager)
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
     * Exposes the underlying plugin manager.
     */
    fun getPluginManager() = pluginManager
}
