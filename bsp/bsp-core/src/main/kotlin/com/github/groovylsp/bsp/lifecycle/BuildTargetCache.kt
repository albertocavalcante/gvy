package com.github.groovylsp.bsp.lifecycle

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.SourceItem
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Thread-safe cache for build targets and their metadata.
 *
 * This cache stores:
 * - Build targets and their capabilities
 * - Source file to build target mappings
 * - Dependency information
 *
 * The cache is invalidated when build targets change (via onBuildTargetDidChange).
 */
class BuildTargetCache {
    private val logger = LoggerFactory.getLogger(BuildTargetCache::class.java)

    @Volatile
    private var targets: Map<BuildTargetIdentifier, BuildTarget> = emptyMap()

    @Volatile
    private var sourceToTargets: Map<Path, List<BuildTargetIdentifier>> = emptyMap()

    /**
     * Update the cache with fresh build targets.
     */
    fun updateTargets(newTargets: List<BuildTarget>) {
        targets = newTargets.associateBy { it.id }
        logger.info("Updated build target cache: ${targets.size} targets")
    }

    /**
     * Update the source-to-target mappings.
     */
    fun updateSourceMappings(sourcesMap: Map<BuildTargetIdentifier, List<SourceItem>>) {
        val newMappings = mutableMapOf<Path, MutableList<BuildTargetIdentifier>>()

        sourcesMap.forEach { (targetId, sources) ->
            sources.forEach { sourceItem ->
                val path = uriToPath(sourceItem.uri).fold(
                    ifLeft = { return@forEach },
                    ifRight = { it },
                )
                newMappings.getOrPut(path) { mutableListOf() }.add(targetId)
            }
        }

        sourceToTargets = newMappings.mapValues { it.value.toList() }
        logger.info("Updated source mappings: ${sourceToTargets.size} source paths tracked")
    }

    /**
     * Get all build targets.
     */
    fun getAllTargets(): List<BuildTarget> = targets.values.toList()

    /**
     * Get a build target by ID.
     */
    fun getTarget(id: BuildTargetIdentifier): BuildTarget? = targets[id]

    /**
     * Find build targets that contain the given source file.
     */
    fun findTargetsForSource(sourcePath: Path): List<BuildTargetIdentifier> {
        // Try exact match first
        sourceToTargets[sourcePath]?.let { return it }

        // Try normalized path
        val normalized = try {
            sourcePath.normalize()
        } catch (e: Exception) {
            logger.debug("Failed to normalize path: $sourcePath")
            return emptyList()
        }

        return sourceToTargets[normalized] ?: emptyList()
    }

    /**
     * Find build targets that are relevant for a file (including parent directories).
     */
    fun findRelevantTargets(filePath: Path): List<BuildTargetIdentifier> {
        val directMatches = findTargetsForSource(filePath)
        if (directMatches.isNotEmpty()) return directMatches

        // Check if file is within any source directory
        val parentPath = filePath.parent ?: return emptyList()
        return findTargetsForSource(parentPath)
    }

    /**
     * Get all target IDs.
     */
    fun getAllTargetIds(): List<BuildTargetIdentifier> = targets.keys.toList()

    /**
     * Filter targets by language.
     */
    fun getTargetsByLanguage(language: String): List<BuildTarget> = targets.values.filter { target ->
        target.languageIds?.contains(language) == true
    }

    /**
     * Clear the cache.
     */
    fun clear() {
        targets = emptyMap()
        sourceToTargets = emptyMap()
        logger.info("Build target cache cleared")
    }

    /**
     * Check if cache is empty.
     */
    fun isEmpty(): Boolean = targets.isEmpty()

    /**
     * Get cache statistics for debugging.
     */
    fun getStats(): CacheStats = CacheStats(
        targetCount = targets.size,
        sourcePathCount = sourceToTargets.size,
    )

    data class CacheStats(val targetCount: Int, val sourcePathCount: Int) {
        override fun toString(): String = "BuildTargetCache(targets=$targetCount, sources=$sourcePathCount)"
    }

    private fun uriToPath(uri: String): Either<String, Path> = try {
        Paths.get(URI.create(uri)).right()
    } catch (e: Exception) {
        logger.debug("Failed to parse URI: $uri - ${e.message}")
        "Invalid URI: $uri".left()
    }
}
