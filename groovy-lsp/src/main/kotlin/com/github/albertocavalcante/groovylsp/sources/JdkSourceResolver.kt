package com.github.albertocavalcante.groovylsp.sources

import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Resolves source code for JDK classes from $JAVA_HOME/lib/src.zip.
 *
 * Handles jrt: URIs which represent classes in the Java runtime:
 * - jrt:/java.base/java/util/Date.class -> java.util.Date
 * - jrt:/java.base/java/text/SimpleDateFormat.class -> java.text.SimpleDateFormat
 *
 * Extracts source files lazily (per-class) to ~/.gls/cache/jdk-sources/
 */
class JdkSourceResolver(
    private val jdkSourceDir: Path = getDefaultJdkSourceDir(),
    private val javaSourceInspector: JavaSourceInspector = JavaSourceInspector(),
) {
    private val logger = LoggerFactory.getLogger(JdkSourceResolver::class.java)

    // Thread-safe cache: className -> extracted source path
    private val extractedSourceCache = ConcurrentHashMap<String, Path>()

    companion object {
        fun getDefaultJdkSourceDir(): Path {
            val home = System.getProperty("user.home")
            return Path.of(home, ".gls", "cache", "jdk-sources")
        }
    }

    /**
     * Resolve JDK source for a class referenced by a jrt: URI.
     *
     * @param jrtUri The jrt: URI (e.g., jrt:/java.base/java/util/Date.class)
     * @param className The fully qualified class name
     * @return SourceNavigator.SourceResult indicating success or failure
     */
    suspend fun resolveJdkSource(jrtUri: URI, className: String): SourceNavigator.SourceResult {
        logger.debug("Resolving JDK source for: {} from {}", className, jrtUri)

        // Check cache first (thread-safe via ConcurrentHashMap)
        extractedSourceCache[className]?.let { cachedPath ->
            if (Files.exists(cachedPath)) {
                logger.debug("Found cached JDK source for: {}", className)
                val inspection = javaSourceInspector.inspectClass(cachedPath, className)
                return SourceNavigator.SourceResult.SourceLocation(
                    uri = cachedPath.toUri(),
                    className = className,
                    lineNumber = inspection?.lineNumber,
                    documentation = inspection?.documentation,
                )
            } else {
                // Remove stale cache entry if file was deleted
                extractedSourceCache.remove(className)
                logger.debug("Removed stale cache entry for: {}", className)
            }
        }

        // Locate src.zip
        val srcZip = findSrcZip() ?: return SourceNavigator.SourceResult.BinaryOnly(
            uri = jrtUri,
            className = className,
            reason = "JDK src.zip not found. Set JAVA_HOME or use a JDK (not JRE).",
        )

        // Parse jrt: URI to get module and class path
        val (moduleName, classPath) = parseJrtUri(jrtUri) ?: return SourceNavigator.SourceResult.BinaryOnly(
            uri = jrtUri,
            className = className,
            reason = "Could not parse jrt: URI: $jrtUri",
        )

        // Extract source file
        val extractedPath = extractSourceFromZip(srcZip, moduleName, classPath, className)
            ?: return SourceNavigator.SourceResult.BinaryOnly(
                uri = jrtUri,
                className = className,
                reason = "Source for $className not found in src.zip",
            )

        // Cache and return
        extractedSourceCache[className] = extractedPath
        logger.info("Extracted JDK source: {} -> {}", className, extractedPath)

        // Inspect the source to get line number and documentation
        val inspection = javaSourceInspector.inspectClass(extractedPath, className)

        return SourceNavigator.SourceResult.SourceLocation(
            uri = extractedPath.toUri(),
            className = className,
            lineNumber = inspection?.lineNumber,
            documentation = inspection?.documentation,
        )
    }

    /**
     * Find src.zip in the JDK installation.
     *
     * Search order:
     * 1. $JAVA_HOME/lib/src.zip (Java 9+)
     * 2. $JAVA_HOME/src.zip (Java 8)
     * 3. System property java.home (current JVM)
     */
    fun findSrcZip(): Path? {
        // Try JAVA_HOME environment variable
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome != null) {
            val srcZipModern = Path.of(javaHome, "lib", "src.zip")
            if (Files.exists(srcZipModern)) {
                logger.debug("Found src.zip at: {}", srcZipModern)
                return srcZipModern
            }

            val srcZipLegacy = Path.of(javaHome, "src.zip")
            if (Files.exists(srcZipLegacy)) {
                logger.debug("Found src.zip at: {}", srcZipLegacy)
                return srcZipLegacy
            }
        }

        // Try current JVM's java.home
        val javaHomeProp = System.getProperty("java.home")
        if (javaHomeProp != null) {
            // java.home points to JRE, parent is JDK
            val jdkHome = Path.of(javaHomeProp).parent
            if (jdkHome != null) {
                val srcZip = jdkHome.resolve("lib").resolve("src.zip")
                if (Files.exists(srcZip)) {
                    logger.debug("Found src.zip at: {}", srcZip)
                    return srcZip
                }
            }

            // Modern JDKs: java.home points directly to JDK
            val srcZipDirect = Path.of(javaHomeProp, "lib", "src.zip")
            if (Files.exists(srcZipDirect)) {
                logger.debug("Found src.zip at: {}", srcZipDirect)
                return srcZipDirect
            }
        }

        logger.warn("Could not find JDK src.zip. JAVA_HOME={}", javaHome)
        return null
    }

    /**
     * Parse a jrt: URI to extract module name and class path.
     *
     * Examples:
     * - jrt:/java.base/java/util/Date.class -> ("java.base", "java/util/Date")
     * - jrt:/java.sql/java/sql/Connection.class -> ("java.sql", "java/sql/Connection")
     *
     * @return Pair of (moduleName, classPath without .class extension) or null
     */
    fun parseJrtUri(jrtUri: URI): Pair<String, String>? {
        if (jrtUri.scheme != "jrt") {
            return null
        }

        // URI path format: /module.name/package/path/ClassName.class
        val path = jrtUri.path ?: return null
        val parts = path.removePrefix("/").split("/", limit = 2)

        if (parts.size < 2) {
            return null
        }

        val moduleName = parts[0]
        val classPath = parts[1].removeSuffix(".class")

        return moduleName to classPath
    }

    /**
     * Extract a single source file from src.zip.
     *
     * Handles both Java 8 and Java 9+ src.zip structures:
     * - Java 8: java/util/Date.java
     * - Java 9+: java.base/java/util/Date.java
     */
    private fun extractSourceFromZip(srcZip: Path, moduleName: String, classPath: String, className: String): Path? {
        val javaPath = "$classPath.java"

        // Possible entry paths in src.zip
        val possibleEntries = listOf(
            "$moduleName/$javaPath", // Java 9+: java.base/java/util/Date.java
            javaPath, // Java 8: java/util/Date.java
        )

        try {
            ZipFile(srcZip.toFile()).use { zip ->
                for (entryPath in possibleEntries) {
                    val entry = zip.getEntry(entryPath)
                    if (entry != null && !entry.isDirectory) {
                        // Create output path
                        val outputPath = jdkSourceDir.resolve(className.replace('.', '/') + ".java")
                        Files.createDirectories(outputPath.parent)

                        // Extract file
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, outputPath, StandardCopyOption.REPLACE_EXISTING)
                        }

                        logger.debug("Extracted {} from src.zip entry: {}", className, entryPath)
                        return outputPath
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to extract source from src.zip for {}: {}", className, e.message)
        }

        return null
    }

    /**
     * Get statistics about JDK source resolution.
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "cachedClasses" to extractedSourceCache.size,
        "jdkSourceDir" to jdkSourceDir.toString(),
        "srcZipLocation" to (findSrcZip()?.toString() ?: "not found"),
    )

    /**
     * Clear the extraction cache.
     */
    fun clearCache(deleteFiles: Boolean = false) {
        if (deleteFiles && Files.exists(jdkSourceDir)) {
            try {
                Files.walk(jdkSourceDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                logger.warn("Failed to delete JDK source cache: {}", e.message)
            }
        }
        extractedSourceCache.clear()
        logger.info("Cleared JDK source cache")
    }
}
