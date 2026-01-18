package com.github.groovylsp.bsp.model

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache of BSP build targets and their metadata.
 *
 * Provides fast lookup operations for:
 * - Finding which target owns a source file
 * - Retrieving target sources and dependencies
 * - Accessing target classpath information
 *
 * Based on Metals' target cache pattern for efficient BSP data access.
 *
 * All operations are thread-safe using [ConcurrentHashMap].
 *
 * ## Usage
 * ```kotlin
 * val cache = BuildTargetCache()
 * cache.updateTargets(targets)
 * cache.updateSources(targetId, sources)
 * val target = cache.findTargetForSource(sourceFile)
 * ```
 */
class BuildTargetCache {
    private val logger = KotlinLogging.logger {}

    /**
     * Dedicated lock for source mapping operations.
     * Protects compound operations on both targetSources and sourceToTarget.
     */
    private val sourcesLock = Any()

    /**
     * Map of target ID to full BuildTarget.
     */
    private val targets = ConcurrentHashMap<BuildTargetIdentifier, BuildTarget>()

    /**
     * Reverse index: source file → target ID.
     */
    private val sourceToTarget = ConcurrentHashMap<Path, BuildTargetIdentifier>()

    /**
     * Map of target ID to list of source paths.
     */
    private val targetSources = ConcurrentHashMap<BuildTargetIdentifier, List<Path>>()

    /**
     * Map of target ID to classpath entries.
     */
    private val targetClasspath = ConcurrentHashMap<BuildTargetIdentifier, List<Path>>()

    // ========== Queries ==========

    /**
     * Returns all cached build targets.
     */
    fun all(): List<BuildTarget> = targets.values.toList()

    /**
     * Finds the build target that owns the given source file.
     *
     * @param file Source file path
     * @return The [BuildTarget] owning this file, or null if not found
     */
    fun findTargetForSource(file: Path): BuildTarget? {
        val targetId = sourceToTarget[file] ?: return null
        return targets[targetId]
    }

    /**
     * Returns all source files for a given target.
     *
     * @param targetId The target identifier
     * @return List of source paths, or empty list if target not found
     */
    fun getTargetSources(targetId: BuildTargetIdentifier): List<Path> = targetSources[targetId] ?: emptyList()

    /**
     * Returns the direct dependencies of a target.
     *
     * @param targetId The target identifier
     * @return List of dependency target IDs, or empty list if target not found
     */
    fun getTargetDependencies(targetId: BuildTargetIdentifier): List<BuildTargetIdentifier> =
        targets[targetId]?.dependencies ?: emptyList()

    /**
     * Returns the classpath entries for a target.
     *
     * @param targetId The target identifier
     * @return List of classpath paths (JARs and directories), or empty list if not cached
     */
    fun getTargetClasspath(targetId: BuildTargetIdentifier): List<Path> = targetClasspath[targetId] ?: emptyList()

    /**
     * Checks if a target exists in the cache.
     *
     * @param targetId The target identifier
     * @return true if the target is cached
     */
    fun contains(targetId: BuildTargetIdentifier): Boolean = targets.containsKey(targetId)

    /**
     * Returns the number of cached targets.
     */
    fun size(): Int = targets.size

    // ========== Updates ==========

    /**
     * Updates the cache with a new list of build targets.
     *
     * This replaces the current target list but preserves source and classpath
     * information unless explicitly updated.
     *
     * @param newTargets List of build targets from workspace/buildTargets
     */
    fun updateTargets(newTargets: List<BuildTarget>) {
        logger.debug { "Updating cache with ${newTargets.size} targets" }

        // Clear old targets not in the new list
        val newTargetIds = newTargets.map { it.id }.toSet()
        val removedTargets = targets.keys.filterNot { it in newTargetIds }
        removedTargets.forEach { invalidate(it) }

        // Add/update new targets
        newTargets.forEach { target ->
            targets[target.id] = target
            logger.trace { "Cached target: ${target.id.uri}" }
        }

        logger.info { "Cache updated: ${targets.size} targets (${removedTargets.size} removed)" }
    }

    /**
     * Updates the source files for a specific target.
     *
     * This rebuilds the reverse index (source → target) for efficient lookups.
     *
     * @param targetId The target identifier
     * @param sources List of source file paths
     */
    fun updateSources(targetId: BuildTargetIdentifier, sources: List<Path>) {
        logger.debug { "Updating sources for target ${targetId.uri}: ${sources.size} files" }

        synchronized(sourcesLock) {
            // Remove old reverse mappings for this target
            val oldSources = targetSources[targetId] ?: emptyList()
            oldSources.forEach { sourceToTarget.remove(it) }

            // Add new sources and rebuild reverse index
            targetSources[targetId] = sources
            sources.forEach { source ->
                sourceToTarget[source] = targetId
            }
        }

        logger.trace { "Updated sources for ${targetId.uri}: ${sources.size} files" }
    }

    /**
     * Updates the classpath for a specific target.
     *
     * @param targetId The target identifier
     * @param classpath List of classpath entries (JARs and directories)
     */
    fun updateClasspath(targetId: BuildTargetIdentifier, classpath: List<Path>) {
        logger.debug { "Updating classpath for target ${targetId.uri}: ${classpath.size} entries" }
        targetClasspath[targetId] = classpath
    }

    /**
     * Refreshes the cache by querying the BSP server.
     *
     * This method will be implemented once [BuildServerConnection] is available.
     * It should:
     * 1. Call workspace/buildTargets
     * 2. For each target, call buildTarget/sources
     * 3. Optionally call buildTarget/dependencySources for classpath
     *
     * @param connection The BSP server connection (to be implemented)
     */
    @Suppress("UnusedParameter") // TODO: Implement when BuildServerConnection refactoring is complete
    suspend fun refresh(connection: Any) {
        // TODO: Implement once BuildServerConnection is available
        // 1. val targetsResult = connection.workspaceBuildTargets().await()
        // 2. updateTargets(targetsResult.targets)
        // 3. for each target: call buildTarget/sources and updateSources
        // 4. for each target: call buildTarget/dependencySources and updateClasspath
        logger.warn { "BuildTargetCache.refresh() not yet implemented - waiting for BuildServerConnection" }
    }

    /**
     * Invalidates a specific target, removing all associated data.
     *
     * @param targetId The target identifier to invalidate
     */
    fun invalidate(targetId: BuildTargetIdentifier) {
        logger.debug { "Invalidating target: ${targetId.uri}" }

        targets.remove(targetId)

        // Remove source mappings atomically
        synchronized(sourcesLock) {
            val sources = targetSources.remove(targetId) ?: emptyList()
            sources.forEach { sourceToTarget.remove(it) }
        }

        // Remove classpath
        targetClasspath.remove(targetId)

        logger.trace { "Invalidated target: ${targetId.uri}" }
    }

    /**
     * Clears all cached data.
     */
    fun clear() {
        logger.info { "Clearing build target cache" }
        targets.clear()
        synchronized(sourcesLock) {
            sourceToTarget.clear()
            targetSources.clear()
        }
        targetClasspath.clear()
    }

    override fun toString(): String = "BuildTargetCache(targets=${targets.size}, sources=${sourceToTarget.size})"
}
