package com.github.albertocavalcante.groovylsp.compilation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Service for discovering and scanning Groovy source files in workspace directories.
 *
 * This service provides utilities for finding Groovy files, converting paths to URIs,
 * and validating file types. It complements WorkspaceManager by focusing specifically
 * on file system scanning operations.
 *
 * @param ioDispatcher Coroutine dispatcher for IO-bound operations (default: Dispatchers.IO)
 */
class WorkspaceScanner(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    companion object {
        private const val GROOVY_EXTENSION = "groovy"
        private const val GRADLE_EXTENSION = "gradle"
        private const val JENKINSFILE_NAME = "Jenkinsfile"
        private const val GVY_EXTENSION = "gvy"
        private const val GY_EXTENSION = "gy"
        private const val GSH_EXTENSION = "gsh"

        private val GROOVY_EXTENSIONS = setOf(
            GROOVY_EXTENSION,
            GRADLE_EXTENSION,
            GVY_EXTENSION,
            GY_EXTENSION,
            GSH_EXTENSION,
        )
    }

    /**
     * Finds all Groovy files in the specified workspace roots.
     * Recursively walks directory trees and filters for Groovy file extensions.
     *
     * @param roots List of root directories to scan
     * @return Sequence of Paths pointing to Groovy source files
     */
    fun findGroovyFiles(roots: List<Path>): Sequence<Path> = sequence {
        for (root in roots) {
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                continue
            }

            try {
                Files.walk(root).use { stream ->
                    stream.asSequence()
                        .filter { it.isRegularFile() }
                        .filter { isGroovyFile(it) }
                        .forEach { yield(it) }
                }
            } catch (e: Exception) {
                // Continue with next root if one fails
                // Errors are expected for inaccessible directories
            }
        }
    }

    /**
     * Finds Groovy files in workspace roots that match a specific pattern.
     * Pattern is matched against the file name (not full path).
     *
     * @param roots List of root directories to scan
     * @param pattern Pattern to match against file names (supports wildcards)
     * @return Sequence of Paths pointing to matching Groovy source files
     */
    fun findGroovyFiles(roots: List<Path>, pattern: String): Sequence<Path> {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .toRegex(RegexOption.IGNORE_CASE)

        return findGroovyFiles(roots).filter { path ->
            regex.matches(path.fileName.toString())
        }
    }

    /**
     * Converts a sequence of Paths to a list of URIs.
     * Filters out paths that cannot be converted to valid URIs.
     *
     * @param paths Sequence of file paths
     * @return List of URIs
     */
    fun pathsToUris(paths: Sequence<Path>): List<URI> = paths
        .mapNotNull { path ->
            try {
                path.toUri()
            } catch (e: Exception) {
                null
            }
        }
        .toList()

    /**
     * Checks if a path points to a Groovy source file.
     * Recognizes standard Groovy extensions (.groovy, .gradle, .gvy, .gy, .gsh)
     * and Jenkinsfile (no extension).
     *
     * @param path The path to check
     * @return true if the file is a Groovy source file, false otherwise
     */
    fun isGroovyFile(path: Path): Boolean {
        val fileName = path.fileName?.toString() ?: return false

        // Check for Jenkinsfile (no extension)
        if (fileName == JENKINSFILE_NAME) {
            return true
        }

        // Check for Groovy file extensions
        return path.extension.lowercase() in GROOVY_EXTENSIONS
    }

    /**
     * Checks if a URI represents a Groovy source file.
     *
     * @param uri The URI to check
     * @return true if the URI represents a Groovy source file, false otherwise
     */
    fun isGroovyFile(uri: URI): Boolean = try {
        isGroovyFile(Path.of(uri))
    } catch (e: Exception) {
        false
    }
}
