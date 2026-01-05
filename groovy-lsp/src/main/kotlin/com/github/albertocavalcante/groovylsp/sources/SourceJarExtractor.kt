package com.github.albertocavalcante.groovylsp.sources

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Extracts and indexes source files from source JARs.
 *
 * Provides mapping from class names to actual source file locations,
 * enabling go-to-definition to navigate to source code rather than
 * just showing "binary definition".
 */
class SourceJarExtractor(private val extractionDir: Path = getDefaultExtractionDir()) {

    private val logger = LoggerFactory.getLogger(SourceJarExtractor::class.java)

    // Cache: sourceJarPath -> Map<className, extractedSourcePath>
    private val extractionCache = ConcurrentHashMap<Path, Map<String, Path>>()

    // Cache: className -> extractedSourcePath (quick lookup)
    private val classToSourceCache = ConcurrentHashMap<String, Path>()

    companion object {
        fun getDefaultExtractionDir(): Path {
            val home = System.getProperty("user.home")
            return Path.of(home, ".gls", "cache", "extracted-sources")
        }
    }

    /**
     * Extract a source JAR and index its contents.
     *
     * @param sourceJarPath Path to the source JAR
     * @return Map of fully qualified class names to extracted source file paths
     */
    fun extractAndIndex(sourceJarPath: Path): Map<String, Path> {
        // Check cache first
        extractionCache[sourceJarPath]?.let { return it }

        if (!Files.exists(sourceJarPath)) {
            logger.warn("Source JAR not found: {}", sourceJarPath)
            return emptyMap()
        }

        val jarFileName = sourceJarPath.fileName.toString().removeSuffix("-sources.jar")
        val outputDir = extractionDir.resolve(jarFileName)

        // Create output directory
        Files.createDirectories(outputDir)
        val normalizedOutputDir = outputDir.normalize()

        val classToSource = mutableMapOf<String, Path>()

        try {
            ZipFile(sourceJarPath.toFile()).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".java") }
                    .forEach { entry ->
                        val outputPath = normalizedOutputDir.resolve(entry.name).normalize()
                        if (!outputPath.startsWith(normalizedOutputDir)) {
                            logger.warn("Zip Slip attempt detected in source JAR: {}", entry.name)
                            return@forEach
                        }

                        // Create parent directories
                        Files.createDirectories(outputPath.parent)

                        // Extract file (use REPLACE_EXISTING for re-extraction after cache clear)
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, outputPath, StandardCopyOption.REPLACE_EXISTING)
                        }

                        // Index: convert path to class name
                        val className = entry.name
                            .removeSuffix(".java")
                            .replace('/', '.')

                        classToSource[className] = outputPath
                        classToSourceCache[className] = outputPath

                        logger.debug("Extracted: {} -> {}", className, outputPath)
                    }
            }

            logger.info("Extracted {} source files from {}", classToSource.size, sourceJarPath)

            // Cache the result
            extractionCache[sourceJarPath] = classToSource

            return classToSource
        } catch (e: Exception) {
            logger.error("Failed to extract source JAR: {}", sourceJarPath, e)
            return emptyMap()
        }
    }

    /**
     * Find the extracted source file for a class name.
     *
     * @param className Fully qualified class name (e.g., "org.jenkinsci.plugins.workflow.steps.Step")
     * @return Path to extracted source file, or null if not found
     */
    fun findSourceForClass(className: String): Path? {
        // Check quick cache first
        classToSourceCache[className]?.let { return it }

        // Handle inner classes - strip inner class part for source file lookup
        val outerClassName = className.substringBefore('$')
        classToSourceCache[outerClassName]?.let { return it }

        return null
    }

    /**
     * Get statistics about extracted sources.
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "extractedJars" to extractionCache.size,
        "indexedClasses" to classToSourceCache.size,
        "extractionDir" to extractionDir.toString(),
    )

    /**
     * Clear all caches and optionally delete extracted files.
     */
    fun clearCache(deleteFiles: Boolean = false) {
        if (deleteFiles && Files.exists(extractionDir)) {
            Files.walk(extractionDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete)
        }

        extractionCache.clear()
        classToSourceCache.clear()
        logger.info("Cleared source extraction cache")
    }
}
