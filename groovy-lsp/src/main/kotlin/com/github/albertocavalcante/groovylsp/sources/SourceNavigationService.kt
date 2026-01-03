package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.buildtool.MavenSourceArtifactResolver
import com.github.albertocavalcante.groovylsp.buildtool.SourceArtifactResolver
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

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
 *
 * Implements [SourceNavigator] interface for testability and dependency injection.
 */
class SourceNavigationService(
    private val sourceResolver: SourceArtifactResolver = MavenSourceArtifactResolver(),
    private val sourceExtractor: SourceJarExtractor = SourceJarExtractor(),
    private val jdkSourceResolver: JdkSourceResolver = JdkSourceResolver(),
    private val javaSourceInspector: JavaSourceInspector = JavaSourceInspector(),
) : SourceNavigator {

    private companion object {
        private const val MIN_MAVEN_COORDINATE_PARTS = 3
    }

    private val logger = LoggerFactory.getLogger(SourceNavigationService::class.java)

    /**
     * Navigate to source code for a class found in the classpath.
     *
     * Handles:
     * - jrt: URIs (JDK classes) -> extracts from $JAVA_HOME/lib/src.zip
     * - jar: URIs (Maven dependencies) -> downloads source JAR from Maven
     *
     * @param classpathUri URI of the class (jrt: or jar:file:...)
     * @param className Fully qualified class name
     * @return SourceResult indicating where to navigate
     */
    override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult {
        logger.debug("Navigating to source for: {} from {}", className, classpathUri)

        // Handle JDK classes (jrt: scheme)
        if (classpathUri.scheme == "jrt") {
            return jdkSourceResolver.resolveJdkSource(classpathUri, className)
        }

        // Step 1: Check if we already have extracted sources
        val existingSource = sourceExtractor.findSourceForClass(className)
        if (existingSource != null) {
            logger.debug("Found cached source for: {}", className)
            val inspection = javaSourceInspector.inspectClass(existingSource, className)
            return SourceNavigator.SourceResult.SourceLocation(
                uri = existingSource.toUri(),
                className = className,
                lineNumber = inspection?.lineNumber,
                documentation = inspection?.documentation,
            )
        }

        // Step 2: Derive Maven coordinates from JAR path
        val jarPath = extractJarPath(classpathUri) ?: return SourceNavigator.SourceResult.BinaryOnly(
            uri = classpathUri,
            className = className,
            reason = "Could not extract JAR path from URI",
        )

        // Step 3: Try to resolve source JAR (Maven or adjacent)
        val sourceJarPath = resolveSourceJar(jarPath) ?: return SourceNavigator.SourceResult.BinaryOnly(
            uri = classpathUri,
            className = className,
            reason = "Source JAR not available for ${jarPath.fileName}",
        )

        // Step 4: Extract and index the source JAR
        sourceExtractor.extractAndIndex(sourceJarPath)

        // Step 5: Find the specific source file
        val sourcePath = sourceExtractor.findSourceForClass(className)
        return if (sourcePath != null) {
            val inspection = javaSourceInspector.inspectClass(sourcePath, className)
            SourceNavigator.SourceResult.SourceLocation(
                uri = sourcePath.toUri(),
                className = className,
                lineNumber = inspection?.lineNumber,
                documentation = inspection?.documentation,
            )
        } else {
            SourceNavigator.SourceResult.BinaryOnly(
                uri = classpathUri,
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
    private fun extractJarPath(classpathUri: URI): Path? {
        val uriString = classpathUri.toString()

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
     * Resolve source JAR for a binary JAR.
     *
     * Resolution order:
     * 1. Try to derive Maven coordinates and download from Maven Central
     * 2. Look for adjacent -sources.jar next to the binary JAR (for local JARs)
     *
     * @param binaryJarPath Path to the binary JAR file
     * @return Path to source JAR if found, null otherwise
     */
    private suspend fun resolveSourceJar(binaryJarPath: Path): Path? {
        // Step 1: Try Maven coordinates derivation and download
        val coords = deriveCoordinates(binaryJarPath)
        if (coords != null) {
            try {
                val sourceJar = sourceResolver.resolveSourceJar(coords.groupId, coords.artifactId, coords.version)
                if (sourceJar != null) {
                    logger.debug("Resolved source JAR via Maven: {}", sourceJar)
                    return sourceJar
                }
            } catch (e: Exception) {
                logger.debug("Failed to resolve source JAR from Maven: {}", e.message)
            }
        }

        // Step 2: Look for adjacent -sources.jar in the same directory
        val adjacentSource = findAdjacentSourceJar(binaryJarPath)
        if (adjacentSource != null) {
            logger.debug("Found adjacent source JAR: {}", adjacentSource)
            return adjacentSource
        }

        logger.debug("No source JAR found for: {}", binaryJarPath)
        return null
    }

    /**
     * Look for a -sources.jar next to a binary JAR file.
     *
     * For example: libs/testlib.jar -> libs/testlib-sources.jar
     */
    private fun findAdjacentSourceJar(binaryJarPath: Path): Path? {
        val fileName = binaryJarPath.fileName.toString()
        if (!fileName.endsWith(".jar")) return null

        val baseName = fileName.removeSuffix(".jar")
        val sourceJarName = "$baseName-sources.jar"
        val sourceJarPath = binaryJarPath.parent?.resolve(sourceJarName)

        return if (sourceJarPath != null && Files.exists(sourceJarPath)) {
            sourceJarPath
        } else {
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

        if (repoIndex == -1 || repoIndex + MIN_MAVEN_COORDINATE_PARTS >= parts.size) {
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
        "extractorStats" to sourceExtractor.getStatistics(),
    )
}
