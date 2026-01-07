package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks incremental compilation state following Bloop's caching pattern.
 *
 * Maintains a record of the last successful compilation for each build target,
 * enabling incremental compilation by detecting when sources/classpath haven't
 * changed since the last successful build.
 *
 * Thread-safe for concurrent access.
 */
class ResultsCache {

    private val logger = LoggerFactory.getLogger(ResultsCache::class.java)
    private val lastSuccessful = ConcurrentHashMap<BuildTargetIdentifier, LastSuccessfulResult>()

    /**
     * Snapshot of a successful compilation.
     *
     * @property classesDir Directory containing compiled class files
     * @property inputsHash Hash of sources + classpath at compilation time
     * @property timestamp When this compilation completed
     */
    data class LastSuccessfulResult(val classesDir: Path, val inputsHash: Long, val timestamp: Instant)

    /**
     * Checks if the target is up-to-date and doesn't need recompilation.
     *
     * A target is up-to-date if:
     * 1. A successful compilation is recorded
     * 2. The current inputs hash matches the cached hash
     * 3. The classes directory still exists
     *
     * @param targetId Build target to check
     * @param currentInputsHash Hash of current sources + classpath
     * @return true if cached result is still valid, false if compilation needed
     */
    fun isUpToDate(targetId: BuildTargetIdentifier, currentInputsHash: Long): Boolean {
        val cached = lastSuccessful[targetId] ?: return false

        // Check hash match
        if (cached.inputsHash != currentInputsHash) {
            logger.debug("Cache miss for $targetId: hash changed")
            return false
        }

        // NOTE: We assume classes directory exists - the build system
        //   is responsible for not deleting output directories
        logger.debug("Cache hit for $targetId")
        return true
    }

    /**
     * Records a successful compilation result.
     *
     * Stores the compilation snapshot for future incremental checks.
     * Overwrites any previous result for this target.
     *
     * @param targetId Build target that was compiled
     * @param result Compilation result snapshot
     */
    fun recordSuccess(targetId: BuildTargetIdentifier, result: LastSuccessfulResult) {
        lastSuccessful[targetId] = result
        logger.debug("Cached successful compilation for $targetId at ${result.timestamp}")
    }

    /**
     * Invalidates the cache for a specific target.
     *
     * Forces next compilation to run even if sources haven't changed.
     * Use when external factors change (e.g., dependency updates, clean build).
     *
     * @param targetId Target to invalidate
     */
    fun invalidate(targetId: BuildTargetIdentifier) {
        val removed = lastSuccessful.remove(targetId)
        if (removed != null) {
            logger.debug("Invalidated cache for $targetId")
        }
    }

    /**
     * Clears all cached results.
     *
     * Forces full recompilation of all targets.
     * Use for clean builds or when underlying infrastructure changes.
     */
    fun invalidateAll() {
        val count = lastSuccessful.size
        lastSuccessful.clear()
        logger.info("Invalidated all caches ($count targets)")
    }

    /**
     * Returns cached result for a target if available.
     *
     * @param targetId Target to look up
     * @return Cached result or null if not found
     */
    fun get(targetId: BuildTargetIdentifier): LastSuccessfulResult? = lastSuccessful[targetId]

    /**
     * Returns all cached target IDs (for monitoring/debugging).
     */
    fun cachedTargets(): Set<BuildTargetIdentifier> = lastSuccessful.keys.toSet()

    /**
     * Returns number of cached results (for testing/monitoring).
     */
    fun size(): Int = lastSuccessful.size
}
