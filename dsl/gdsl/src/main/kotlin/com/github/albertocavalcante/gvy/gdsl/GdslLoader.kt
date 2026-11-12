// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gdsl

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val logger = KotlinLogging.logger {}

    /**
     * Loads a single GDSL file from the given path.
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadGdslFile(pathString: String): GdslLoadResult = try {
        val path = Paths.get(pathString)
        val refusal = refusalReason(path)
        if (refusal != null) {
            logger.debug { "Not loading GDSL file: $refusal" }
            GdslLoadResult(pathString, error = "$refusal: $pathString")
        } else {
            val content = Files.readString(path)
            logger.info { "Loaded GDSL file: $pathString (${content.length} characters)" }
            GdslLoadResult(pathString, content = content)
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to load GDSL file: $pathString" }
        GdslLoadResult(pathString, error = "Error loading file: ${e.message}")
    }

    /**
     * Why [path] is not read, or null to read it. The size is checked before reading: a huge file would
     * exhaust memory before the reader's own character limit applies.
     */
    private fun refusalReason(path: Path): String? = when {
        !Files.exists(path) -> "File not found"
        !Files.isRegularFile(path) -> "Not a regular file"
        Files.size(path) > GdslExecutor.MAX_SOURCE_SIZE -> "File is larger than ${GdslExecutor.MAX_SOURCE_SIZE} bytes"
        else -> null
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
        logger.warn(e) { "Failed to expand GDSL pattern: $pattern" }
        emptyList()
    }
}
