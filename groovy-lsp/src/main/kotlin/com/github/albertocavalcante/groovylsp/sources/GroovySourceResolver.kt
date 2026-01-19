package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.gvy.build.MavenSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Resolves source code for Groovy GDK classes from groovy-sources.jar.
 *
 * Similar to JdkSourceResolver, this class:
 * 1. Detects Groovy version from classpath
 * 2. Downloads groovy-sources.jar using MavenSourceArtifactResolver
 * 3. Extracts and parses GDK source files using JavaSourceInspector
 * 4. Builds an index mapping method signatures to parameter names
 *
 * This enables real parameter names (e.g., "closure", "predicate") instead of
 * synthetic names (e.g., "arg0", "arg1") for GDK methods like .each, .collect, .find.
 */
class GroovySourceResolver(
    private val groovySourceDir: Path = getDefaultGroovySourceDir(),
    private val javaSourceInspector: JavaSourceInspector = JavaSourceInspector(),
    private val sourceArtifactResolver: SourceArtifactResolver = MavenSourceArtifactResolver(),
) {
    private val logger = KotlinLogging.logger {}

    // Cache is bounded by GDK_CLASSES size (~8 classes), so no LRU eviction needed
    private val extractedSourceCache = ConcurrentHashMap<String, Path>()

    // Index mapping method signatures to parameter names
    private val methodIndex = GdkMethodIndex()

    companion object {
        /**
         * GDK classes to index for parameter name resolution.
         * These are the main extension method providers in Groovy.
         */
        val GDK_CLASSES = listOf(
            "org.codehaus.groovy.runtime.DefaultGroovyMethods",
            "org.codehaus.groovy.runtime.StringGroovyMethods",
            "org.codehaus.groovy.runtime.DateGroovyMethods",
            "org.codehaus.groovy.runtime.EncodingGroovyMethods",
            "org.codehaus.groovy.runtime.IOGroovyMethods",
            "org.codehaus.groovy.runtime.ProcessGroovyMethods",
            "org.codehaus.groovy.runtime.ResourceGroovyMethods",
            "org.codehaus.groovy.vmplugin.v8.PluginDefaultGroovyMethods",
        )

        fun getDefaultGroovySourceDir(): Path {
            val home = System.getProperty("user.home")
            return Path.of(home, ".gls", "cache", "groovy-sources")
        }
    }

    /**
     * Initialize the resolver by detecting Groovy version and building the index.
     * This should be called once on startup.
     *
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initialize(): Boolean {
        logger.info { "Initializing Groovy source resolver..." }

        // Detect Groovy version from classpath
        val groovyVersion = detectGroovyVersion()
        if (groovyVersion == null) {
            logger.warn { "Could not detect Groovy version from classpath" }
            return false
        }

        logger.info { "Detected Groovy version: $groovyVersion" }

        // Determine Maven coordinates based on Groovy version
        val coordinates = getGroovyMavenCoordinates(groovyVersion)
        logger.debug {
            "Using Maven coordinates: ${coordinates.groupId}:${coordinates.artifactId}:${coordinates.version}"
        }

        // Download or locate sources JAR
        val sourcesJar = sourceArtifactResolver.resolveSourceJar(
            coordinates.groupId,
            coordinates.artifactId,
            coordinates.version,
        )

        if (sourcesJar == null) {
            logger.warn { "Could not resolve Groovy sources JAR for version $groovyVersion" }
            return false
        }

        logger.info { "Resolved Groovy sources JAR: $sourcesJar" }

        // Extract and index GDK source files
        return indexGdkSources(sourcesJar)
    }

    /**
     * Get parameter names for a GDK method from the index.
     *
     * @param originClassName Simple class name (e.g., "DefaultGroovyMethods")
     * @param methodName Method name (e.g., "each")
     * @param parameterTypes Parameter types excluding the "self" parameter (e.g., ["Closure"])
     * @return List of parameter names, or null if not found in index
     */
    fun getParameterNames(originClassName: String, methodName: String, parameterTypes: List<String>): List<String>? =
        methodIndex.getParameterNames(originClassName, methodName, parameterTypes)

    /**
     * Detect Groovy version from the classpath.
     *
     * Looks for GroovySystem class and reads its version.
     */
    private fun detectGroovyVersion(): String? = try {
        val groovySystemClass = Class.forName("groovy.lang.GroovySystem")
        val versionMethod = groovySystemClass.getMethod("getVersion")
        versionMethod.invoke(null) as? String
    } catch (e: Exception) {
        logger.debug(e) { "Failed to detect Groovy version via GroovySystem.getVersion()" }
        null
    }

    /**
     * Get Maven coordinates for Groovy based on version.
     *
     * Groovy 4.x: org.apache.groovy:groovy:X.Y.Z
     * Groovy 3.x/2.x: org.codehaus.groovy:groovy-all:X.Y.Z (fallback to groovy:X.Y.Z)
     */
    private fun getGroovyMavenCoordinates(version: String): MavenCoordinates {
        val majorVersion = version.split(".").firstOrNull()?.toIntOrNull() ?: 4

        return if (majorVersion >= 4) {
            MavenCoordinates("org.apache.groovy", "groovy", version)
        } else {
            // For Groovy 3.x and earlier, try groovy-all first
            MavenCoordinates("org.codehaus.groovy", "groovy", version)
        }
    }

    /**
     * Index GDK source files from the sources JAR.
     *
     * @param sourcesJar Path to the groovy-sources.jar
     * @return true if indexing succeeded, false otherwise
     */
    private fun indexGdkSources(sourcesJar: Path): Boolean {
        if (!Files.exists(sourcesJar)) {
            logger.warn { "Sources JAR does not exist: $sourcesJar" }
            return false
        }

        var indexedCount = 0

        @Suppress("TooGenericExceptionCaught") // Catch-all for JAR extraction errors
        try {
            JarFile(sourcesJar.toFile()).use { jar ->
                for (gdkClassName in GDK_CLASSES) {
                    val extractedPath = extractSourceFromJar(jar, gdkClassName)
                    if (extractedPath != null) {
                        // Extract all method parameters from this GDK class
                        val methodParams = javaSourceInspector.extractAllMethodParameters(extractedPath, gdkClassName)
                        logger.debug { "Extracted ${methodParams.size} methods from $gdkClassName" }

                        // Add to index
                        val simpleClassName = gdkClassName.substringAfterLast('.')
                        methodParams.forEach { (signature, paramNames) ->
                            // signature format: "methodName(Type1,Type2,...)"
                            val methodName = signature.substringBefore('(')
                            val paramTypesStr = signature.substringAfter('(').substringBefore(')')
                            val paramTypes = if (paramTypesStr.isBlank()) {
                                emptyList()
                            } else {
                                paramTypesStr.split(',')
                            }

                            // GDK methods have the "self" parameter as the first parameter
                            // We need to skip it when indexing
                            if (paramNames.size > 1) {
                                methodIndex.addMethod(
                                    simpleClassName,
                                    methodName,
                                    paramTypes.drop(1), // Skip first "self" parameter type
                                    paramNames.drop(1), // Skip first "self" parameter name
                                )
                                indexedCount++
                            }
                        }
                    }
                }
            }

            logger.info { "Successfully indexed $indexedCount GDK methods from sources" }
            return indexedCount > 0
        } catch (e: Exception) {
            logger.error(e) { "Failed to index GDK sources from JAR: $sourcesJar" }
            return false
        }
    }

    /**
     * Extract a single source file from the sources JAR.
     *
     * @param jar The JAR file to extract from
     * @param className Fully qualified class name (e.g., "org.codehaus.groovy.runtime.DefaultGroovyMethods")
     * @return Path to extracted source file, or null if not found
     */
    private fun extractSourceFromJar(jar: JarFile, className: String): Path? {
        // Check cache first
        extractedSourceCache[className]?.let { cachedPath ->
            if (Files.exists(cachedPath)) {
                logger.debug { "Found cached source for: $className" }
                return cachedPath
            } else {
                extractedSourceCache.remove(className)
            }
        }

        val javaPath = className.replace('.', '/') + ".java"

        val entry = jar.getEntry(javaPath)
        if (entry == null || entry.isDirectory) {
            logger.debug { "Source file not found in JAR: $javaPath" }
            return null
        }

        @Suppress("TooGenericExceptionCaught") // Catch-all for file extraction errors
        try {
            // Create output path
            val outputPath = groovySourceDir.resolve(javaPath)
            Files.createDirectories(outputPath.parent)

            // Extract file
            jar.getInputStream(entry).use { input ->
                Files.copy(input, outputPath, StandardCopyOption.REPLACE_EXISTING)
            }

            // Cache the path
            extractedSourceCache[className] = outputPath
            logger.debug { "Extracted source: $className -> $outputPath" }

            return outputPath
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract source from JAR for $className" }
            return null
        }
    }

    /**
     * Get statistics about the resolver.
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "cachedSources" to extractedSourceCache.size,
        "groovySourceDir" to groovySourceDir.toString(),
        "indexStats" to methodIndex.getStatistics(),
    )

    /**
     * Clear the cache and index.
     */
    fun clearCache(deleteFiles: Boolean = false) {
        if (deleteFiles && Files.exists(groovySourceDir)) {
            try {
                Files.walk(groovySourceDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete Groovy source cache" }
            }
        }
        extractedSourceCache.clear()
        methodIndex.clear()
        logger.info { "Cleared Groovy source cache" }
    }

    /**
     * Maven coordinates for resolving artifacts.
     */
    private data class MavenCoordinates(val groupId: String, val artifactId: String, val version: String)
}
