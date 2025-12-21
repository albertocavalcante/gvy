package com.github.albertocavalcante.groovyjenkins

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Loads and extracts source files from Jenkins shared library source JARs.
 * Extracts sources to a temporary directory for compilation and navigation.
 */
class LibrarySourceLoader {
    private val logger = LoggerFactory.getLogger(LibrarySourceLoader::class.java)
    private val extractionDir: Path = Files.createTempDirectory("jenkins-lib-sources")

    init {
        // Clean up on JVM shutdown
        Runtime.getRuntime().addShutdownHook(
            Thread {
                cleanupExtractionDirectory()
            },
        )
    }

    private fun cleanupExtractionDirectory() {
        safeDeleteDirectory(extractionDir) { e ->
            logger.warn("Failed to clean up library source extraction directory", e)
        }
    }

    /**
     * Extracts source files from a shared library source JAR.
     *
     * @param library The shared library configuration
     * @return Path to the extracted source directory, or null if extraction failed
     */
    fun extractSources(library: SharedLibrary): Path? {
        val sourcesJar = library.sourcesJar ?: return null

        val sourcesPath = parseSourcesPath(sourcesJar) ?: return null

        if (!Files.exists(sourcesPath) || !Files.isRegularFile(sourcesPath)) {
            logger.debug("Sources JAR not found: $sourcesJar")
            return null
        }

        val targetDir = extractionDir.resolve(library.name)

        // Skip extraction if already extracted
        if (Files.exists(targetDir) && Files.isDirectory(targetDir)) {
            logger.debug("Sources already extracted for library: ${library.name}")
            return targetDir
        }

        return performExtraction(sourcesPath, targetDir, library.name)
    }

    private fun parseSourcesPath(sourcesJar: String): Path? = try {
        Path.of(sourcesJar)
    } catch (e: java.nio.file.InvalidPathException) {
        logger.warn("Invalid sources JAR path: $sourcesJar", e)
        null
    }

    private fun performExtraction(sourcesPath: Path, targetDir: Path, libraryName: String): Path? = try {
        Files.createDirectories(targetDir)
        extractJarContents(sourcesPath, targetDir)
        logger.info("Extracted sources for library '$libraryName' to $targetDir")
        targetDir
    } catch (e: java.io.IOException) {
        logger.error("Failed to extract sources for library '$libraryName'", e)
        cleanupPartialExtraction(targetDir)
        null
    }

    private fun cleanupPartialExtraction(targetDir: Path) {
        safeDeleteDirectory(targetDir) { e ->
            logger.warn("Failed to clean up partial extraction", e)
        }
    }

    /**
     * Extracts .groovy and .java files from a JAR to a target directory.
     * Includes Zip Slip protection to prevent path traversal attacks.
     */
    private fun extractJarContents(jarPath: Path, targetDir: Path) {
        val normalizedTargetDir = targetDir.normalize()

        JarFile(jarPath.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { entry -> isSourceFile(entry.name) && !entry.isDirectory }
                .mapNotNull { entry ->
                    validateAndResolve(entry.name, normalizedTargetDir).getOrNull()?.let {
                        entry to it
                    }
                }
                .forEach { (entry, targetFile) ->
                    Files.createDirectories(targetFile.parent)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, targetFile)
                    }
                }
        }
    }

    /**
     * Validates that the resolved path stays within the target directory (Zip Slip protection).
     * Returns Either.Left with error message if path traversal detected, Either.Right with safe path otherwise.
     */
    private fun validateAndResolve(entryName: String, targetDir: Path): Either<String, Path> {
        val targetFile = targetDir.resolve(entryName).normalize()
        return if (targetFile.startsWith(targetDir)) {
            targetFile.right()
        } else {
            logger.warn("Zip Slip attack detected: $entryName attempts to escape extraction directory")
            "Path traversal detected: $entryName".left()
        }
    }

    /**
     * Gets all currently extracted library source directories.
     * Uses .use {} to properly close the directory stream.
     */
    fun getExtractedSourceRoots(): List<Path> = if (Files.exists(extractionDir)) {
        Files.list(extractionDir).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList()
        }
    } else {
        emptyList()
    }

    /**
     * Clears all extracted sources (useful for testing or re-extraction).
     */
    fun clearExtractedSources() {
        try {
            if (Files.exists(extractionDir)) {
                deleteDirectoryContents(extractionDir)
            }
            Files.createDirectories(extractionDir)
        } catch (e: java.io.IOException) {
            logger.warn("Failed to clear extracted sources", e)
        }
    }

    private fun deleteDirectoryContents(directory: Path) {
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Safely deletes a directory if it exists, calling onError if deletion fails.
     */
    private inline fun safeDeleteDirectory(directory: Path, onError: (java.io.IOException) -> Unit) {
        if (!Files.exists(directory)) return
        try {
            deleteDirectoryContents(directory)
        } catch (e: java.io.IOException) {
            onError(e)
        }
    }

    companion object {
        private val SOURCE_EXTENSIONS = listOf(".groovy", ".java")

        /**
         * Checks if a file name has a source code extension.
         */
        private fun isSourceFile(name: String): Boolean = SOURCE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }
}
