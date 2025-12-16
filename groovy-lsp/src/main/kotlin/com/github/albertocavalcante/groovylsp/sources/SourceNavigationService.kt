package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.buildtool.MavenSourceArtifactResolver
import com.github.albertocavalcante.groovylsp.buildtool.SourceArtifactResolver
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for navigating to source code from binary class references.
 *
 * Coordinates between:
 * - Source artifact resolution (downloading source JARs from Maven)
 * - Source JAR extraction (extracting .java files)
 * - Line number mapping (finding specific declarations)
 *
 * This enables "Go to Definition" to navigate directly to source code
 * rather than showing "binary class from JAR".
 */
class SourceNavigationService(
    private val sourceResolver: SourceArtifactResolver = MavenSourceArtifactResolver(),
    private val sourceExtractor: SourceJarExtractor = SourceJarExtractor(),
) {

    private val logger = LoggerFactory.getLogger(SourceNavigationService::class.java)

    // Cache: jar URI -> source JAR path (already downloaded)
    private val sourceJarCache = ConcurrentHashMap<String, Path?>()

    /**
     * Result of source navigation - either found source or binary fallback.
     */
    sealed class SourceResult {
        /**
         * Source code location found.
         */
        data class SourceLocation(val uri: URI, val className: String, val lineNumber: Int? = null) : SourceResult()

        /**
         * No source available - binary reference only.
         */
        data class BinaryOnly(val uri: URI, val className: String, val reason: String) : SourceResult()
    }

    /**
     * Navigate to source code for a class found in a JAR.
     *
     * @param jarUri URI of the binary JAR (e.g., jar:file:///path/to/lib.jar!/com/example/Foo.class)
     * @param className Fully qualified class name
     * @return SourceResult indicating where to navigate
     */
    suspend fun navigateToSource(jarUri: URI, className: String): SourceResult {
        logger.debug("Navigating to source for: {} from {}", className, jarUri)

        // Step 1: Check if we already have extracted sources
        val existingSource = sourceExtractor.findSourceForClass(className)
        if (existingSource != null) {
            logger.debug("Found cached source for: {}", className)
            return SourceResult.SourceLocation(
                uri = existingSource.toUri(),
                className = className,
            )
        }

        // Step 2: Derive Maven coordinates from JAR path
        val jarPath = extractJarPath(jarUri) ?: return SourceResult.BinaryOnly(
            uri = jarUri,
            className = className,
            reason = "Could not extract JAR path from URI",
        )

        val coords = deriveCoordinates(jarPath) ?: return SourceResult.BinaryOnly(
            uri = jarUri,
            className = className,
            reason = "Could not determine Maven coordinates for JAR",
        )

        // Step 3: Download source JAR
        val sourceJarPath = try {
            sourceResolver.resolveSourceJar(coords.groupId, coords.artifactId, coords.version)
        } catch (e: Exception) {
            logger.debug("Failed to resolve source JAR: {}", e.message)
            null
        }

        if (sourceJarPath == null) {
            return SourceResult.BinaryOnly(
                uri = jarUri,
                className = className,
                reason = "Source JAR not available for ${coords.groupId}:${coords.artifactId}:${coords.version}",
            )
        }

        // Step 4: Extract and index the source JAR
        sourceExtractor.extractAndIndex(sourceJarPath)

        // Step 5: Find the specific source file
        val sourcePath = sourceExtractor.findSourceForClass(className)
        return if (sourcePath != null) {
            SourceResult.SourceLocation(
                uri = sourcePath.toUri(),
                className = className,
            )
        } else {
            SourceResult.BinaryOnly(
                uri = jarUri,
                className = className,
                reason = "Class $className not found in extracted sources",
            )
        }
    }

    /**
     * Extract the JAR file path from a jar: URI.
     *
     * Input: jar:file:///path/to/library.jar!/com/example/Foo.class
     * Output: /path/to/library.jar
     */
    private fun extractJarPath(jarUri: URI): Path? {
        val uriString = jarUri.toString()

        if (!uriString.startsWith("jar:file:")) {
            return null
        }

        // Extract path between "jar:file:" and "!"
        val jarPath = uriString
            .removePrefix("jar:file:")
            .substringBefore("!")

        return try {
            Path.of(jarPath)
        } catch (e: Exception) {
            logger.debug("Failed to parse JAR path: {}", jarPath)
            null
        }
    }

    /**
     * Derive Maven coordinates from a JAR file path.
     *
     * Uses common Maven repository layout patterns:
     * - ~/.m2/repository/group/artifact/version/artifact-version.jar
     * - ~/.gradle/caches/.../group/artifact/version/.../artifact-version.jar
     */
    private fun deriveCoordinates(jarPath: Path): MavenCoordinates? {
        val pathStr = jarPath.toString()
        val fileName = jarPath.fileName.toString()

        // Try to extract from Maven repository path
        if (pathStr.contains(".m2/repository") || pathStr.contains(".gradle/caches")) {
            return extractFromMavenPath(jarPath, fileName)
        }

        // Try to extract from filename pattern: name-version.jar
        return extractFromFilename(fileName)
    }

    /**
     * Extract coordinates from Maven repository path structure.
     */
    private fun extractFromMavenPath(jarPath: Path, fileName: String): MavenCoordinates? {
        val parts = jarPath.toString().split("/")
        val repoIndex = parts.indexOfFirst { it == "repository" || it == "caches" }

        if (repoIndex == -1 || repoIndex + 3 >= parts.size) {
            return extractFromFilename(fileName)
        }

        // Maven layout: .../repository/group/parts/.../artifact/version/artifact-version.jar
        // Find version directory (parent of JAR file)
        val versionIndex = parts.size - 2
        val version = parts[versionIndex]

        // Find artifact directory (parent of version)
        val artifactIndex = versionIndex - 1
        val artifactId = parts[artifactIndex]

        // Group is everything between repo and artifact
        val groupParts = parts.subList(repoIndex + 1, artifactIndex)
        val groupId = groupParts.joinToString(".")

        // Validate the extracted coordinates
        return if (groupId.isNotBlank() && artifactId.isNotBlank() && version.isNotBlank()) {
            MavenCoordinates(groupId, artifactId, version)
        } else {
            null
        }
    }

    /**
     * Extract coordinates from filename pattern.
     */
    private fun extractFromFilename(fileName: String): MavenCoordinates? {
        // Pattern: artifact-version.jar or artifact-version-classifier.jar
        val baseName = fileName.removeSuffix(".jar")

        // Find the last dash followed by a version-like string
        val versionPattern = Regex("-([0-9]+\\..*?)(-[a-zA-Z]+)?$")
        val match = versionPattern.find(baseName) ?: return null

        val version = match.groupValues[1]
        val artifactId = baseName.substringBefore("-$version")

        // Without path info, we can't determine group - use artifactId as fallback
        return MavenCoordinates(artifactId, artifactId, version)
    }

    /**
     * Maven coordinates holder.
     */
    data class MavenCoordinates(val groupId: String, val artifactId: String, val version: String)

    /**
     * Get service statistics.
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "sourceJarsCached" to sourceJarCache.size,
        "extractorStats" to sourceExtractor.getStatistics(),
    )
}
