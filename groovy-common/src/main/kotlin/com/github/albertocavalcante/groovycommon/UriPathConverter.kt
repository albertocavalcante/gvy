package com.github.albertocavalcante.groovycommon

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Utility for safely converting URIs to file system Paths.
 *
 * Handles non-file URI schemes (untitled:, vscode-notebook:, http:, etc.)
 * gracefully by returning null instead of throwing exceptions.
 *
 * NOTE: VSCode sends various URI schemes for virtual documents that
 * cannot be converted to file system paths. This is expected behavior,
 * not an error condition.
 */
object UriPathConverter {
    private val logger = KotlinLogging.logger {}

    /**
     * Converts a URI to a Path, returning null if the URI cannot be converted.
     *
     * @param uri The URI to convert
     * @return The Path if conversion succeeds, null otherwise
     */
    fun toPath(uri: URI): Path? {
        // Fast path: reject non-file schemes immediately
        if (!isFileUri(uri)) {
            logger.debug { "Skipping non-file URI scheme: ${uri.scheme}" }
            return null
        }

        // Attempt conversion for file:// URIs
        return runCatching { Paths.get(uri) }
            .onFailure { e ->
                when (e) {
                    is FileSystemNotFoundException,
                    is InvalidPathException,
                    is IllegalArgumentException,
                    ->
                        logger.debug(e) { "Cannot convert URI to path: $uri" }
                    is SecurityException ->
                        logger.warn(e) { "Security exception for URI: $uri" }
                    else -> throw e // Rethrow unexpected exceptions
                }
            }
            .getOrNull()
    }

    /**
     * Checks if the URI has a file:// scheme.
     *
     * @param uri The URI to check
     * @return true if the URI scheme is "file", false otherwise
     */
    fun isFileUri(uri: URI): Boolean = uri.scheme == "file"
}
