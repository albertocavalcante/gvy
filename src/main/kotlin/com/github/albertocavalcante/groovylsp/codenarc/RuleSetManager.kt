package com.github.albertocavalcante.groovylsp.codenarc

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Singleton manager for CodeNarc ruleset files with memory efficiency and caching.
 *
 * This manager provides:
 * - Memory efficient caching of compiled rulesets
 * - Automatic temp file management for DSL rulesets
 * - Thread-safe concurrent access
 * - Proper cleanup on shutdown
 *
 * ## Architecture Notes
 * CodeNarc's `ruleSetString` property expects JSON format only. When DSL format
 * is provided, it causes JsonException. This manager works around that by:
 * 1. Writing DSL rulesets to temporary .groovy files
 * 2. Using `ruleSetFiles` property instead of `ruleSetString`
 * 3. Caching temp files by content hash for memory efficiency
 */
@Suppress("TooGenericExceptionCaught") // RuleSet management needs robust error handling
object RuleSetManager {

    private val logger = LoggerFactory.getLogger(RuleSetManager::class.java)

    // Thread-safe cache for temp files by content hash
    private val tempFileCache = ConcurrentHashMap<String, Path>()

    // Track all temp files for cleanup
    private val allTempFiles = ConcurrentHashMap.newKeySet<Path>()

    // Initialize cleanup hook
    init {
        // Register shutdown hook to clean up temp files
        Runtime.getRuntime().addShutdownHook(
            Thread {
                try {
                    cleanup()
                } catch (e: Exception) {
                    logger.warn("Error during RuleSetManager cleanup", e)
                }
            },
        )
    }

    /**
     * Gets a temporary ruleset file for the given content.
     *
     * This method:
     * 1. Detects if content is DSL or JSON format
     * 2. For DSL: Creates/reuses temp .groovy file
     * 3. For JSON: Creates/reuses temp .json file
     * 4. Caches by content hash for memory efficiency
     *
     * @param content The ruleset content (DSL or JSON)
     * @return Path to temporary file that can be used with CodeNarc
     */
    fun getTempRulesetFile(content: String): Path {
        val contentHash = calculateContentHash(content)

        // Check cache first for memory efficiency
        tempFileCache[contentHash]?.let { cachedPath ->
            if (cachedPath.exists()) {
                logger.debug("Reusing cached ruleset file: $cachedPath")
                return cachedPath
            } else {
                // File was deleted, remove from cache
                tempFileCache.remove(contentHash)
                allTempFiles.remove(cachedPath)
            }
        }

        // Determine file extension based on content format
        val isJsonFormat = isJsonFormat(content)
        val extension = if (isJsonFormat) ".json" else ".groovy"
        val prefix = if (isJsonFormat) "codenarc-rules-json-" else "codenarc-rules-dsl-"

        // Create new temp file
        val tempFile = Files.createTempFile(prefix, extension)
        tempFile.writeText(content)

        // Don't set deleteOnExit here - we manage cleanup manually
        // tempFile.toFile().deleteOnExit() would delete too early

        // Cache the file
        tempFileCache[contentHash] = tempFile
        allTempFiles.add(tempFile)

        logger.debug(
            "Created new {} ruleset file: {}",
            if (isJsonFormat) "JSON" else "DSL",
            tempFile,
        )

        return tempFile
    }

    /**
     * Detects if the given content is in JSON format.
     *
     * This is a simple heuristic that checks if the content looks like JSON.
     * CodeNarc expects JSON to be a JSON object starting with '{' and ending with '}'.
     */
    fun isJsonFormat(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    /**
     * Calculates SHA-256 hash of the content for caching purposes.
     */
    private fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the number of cached temp files (for testing/monitoring).
     */
    fun getCachedFileCount(): Int = tempFileCache.size

    /**
     * Returns the total number of temp files created (for testing/monitoring).
     */
    fun getTotalFileCount(): Int = allTempFiles.size

    /**
     * Manually cleans up a specific temp file and removes it from cache.
     *
     * @param path The temp file path to clean up
     * @return true if file was deleted, false if it didn't exist
     */
    fun cleanupFile(path: Path): Boolean {
        val deleted = path.deleteIfExists()
        if (deleted) {
            // Remove from all caches
            tempFileCache.values.removeIf { it == path }
            allTempFiles.remove(path)
            logger.debug("Cleaned up temp ruleset file: $path")
        }
        return deleted
    }

    /**
     * Cleans up all temporary files created by this manager.
     *
     * This is automatically called on JVM shutdown, but can be called manually
     * for testing or when the LSP server is shutting down.
     */
    fun cleanup() {
        logger.debug("Starting RuleSetManager cleanup for {} temp files", allTempFiles.size)

        var deletedCount = 0
        val filesToDelete = allTempFiles.toList() // Create a copy to avoid ConcurrentModificationException

        for (tempFile in filesToDelete) {
            try {
                if (tempFile.deleteIfExists()) {
                    deletedCount++
                }
            } catch (e: Exception) {
                logger.warn("Failed to delete temp ruleset file: $tempFile", e)
            }
        }

        // Clear all caches
        tempFileCache.clear()
        allTempFiles.clear()

        logger.info("RuleSetManager cleanup completed: deleted $deletedCount temp files")
    }

    /**
     * For testing: forces cache invalidation and cleanup.
     * This should only be used in tests.
     */
    internal fun clearCacheForTesting() {
        cleanup()
    }
}
