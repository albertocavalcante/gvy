package com.github.albertocavalcante.groovygdsl

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Result of loading a GDSL file.
 */
data class GdslLoadResult(val path: String, val content: String? = null, val error: String? = null) {
    val isSuccess: Boolean get() = content != null
    val isFailure: Boolean get() = error != null
}

/**
 * Result of loading multiple GDSL files.
 */
data class GdslLoadResults(val successful: List<GdslLoadResult>, val failed: List<GdslLoadResult>)

/**
 * Loads GDSL (Groovy Domain Specific Language) files for Jenkins pipeline metadata.
 */
class GdslLoader {
    private val logger = LoggerFactory.getLogger(GdslLoader::class.java)

    /**
     * Loads a single GDSL file from the given path.
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadGdslFile(pathString: String): GdslLoadResult {
        return try {
            val path = Paths.get(pathString)
            if (!Files.exists(path)) {
                logger.debug("GDSL file not found: $pathString")
                return GdslLoadResult(pathString, error = "File not found: $pathString")
            }

            if (!Files.isRegularFile(path)) {
                logger.debug("GDSL path is not a regular file: $pathString")
                return GdslLoadResult(pathString, error = "Not a regular file: $pathString")
            }

            val content = Files.readString(path)
            logger.info("Loaded GDSL file: $pathString (${content.length} characters)")
            GdslLoadResult(pathString, content = content)
        } catch (e: Exception) {
            logger.warn("Failed to load GDSL file: $pathString", e)
            GdslLoadResult(pathString, error = "Error loading file: ${e.message}")
        }
    }

    /**
     * Loads multiple GDSL files from the given paths.
     */
    fun loadAllGdslFiles(paths: List<String>): GdslLoadResults {
        val results = paths.map { loadGdslFile(it) }
        return GdslLoadResults(
            successful = results.filter { it.isSuccess },
            failed = results.filter { it.isFailure },
        )
    }

    /**
     * Loads GDSL files supporting glob patterns.
     */
    fun loadGdslFilesWithGlob(patterns: List<String>, workspaceRoot: Path): GdslLoadResults {
        val expandedPaths = patterns.flatMap { pattern ->
            expandGlobPattern(pattern, workspaceRoot)
        }
        return loadAllGdslFiles(expandedPaths)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun expandGlobPattern(pattern: String, workspaceRoot: Path): List<String> = try {
        val path = Paths.get(pattern)
        if (path.isAbsolute) {
            // Absolute path - use as is
            if (Files.exists(path)) listOf(pattern) else emptyList()
        } else {
            // Relative path - resolve against workspace root
            val resolved = workspaceRoot.resolve(pattern)
            if (Files.exists(resolved)) listOf(resolved.toString()) else emptyList()
        }
    } catch (e: Exception) {
        logger.warn("Failed to expand GDSL pattern: $pattern", e)
        emptyList()
    }
}
