package com.github.albertocavalcante.groovylsp.buildtool

import org.apache.maven.model.Model
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects Groovy version from build files (Maven POM, Gradle build scripts).
 *
 * This is used to provide better error messages when Groovy version is incompatible
 * with the running JDK (e.g., Groovy 3.0.x on JDK 17+).
 */
class GroovyVersionDetector {
    private val logger = LoggerFactory.getLogger(GroovyVersionDetector::class.java)

    /**
     * Groovy version information extracted from a build file.
     *
     * @property version Full version string (e.g., "4.0.15", "3.0.9-beta-1").
     * @property majorMinor Major.minor version (e.g., "4.0", "3.0").
     * @property source Source file where version was found (e.g., "pom.xml", "build.gradle").
     */
    data class GroovyVersionInfo(val version: String, val majorMinor: String, val source: String)

    /**
     * Detects Groovy version from a Maven POM model.
     *
     * Checks in order:
     * 1. Direct dependency declarations (dependencies)
     * 2. Dependency management section
     * 3. Properties (groovy.version)
     *
     * @param model Maven model to analyze.
     * @return Groovy version info or null if not found.
     */
    fun detectFromMaven(model: Model): GroovyVersionInfo? {
        // Check direct dependencies first
        model.dependencies?.forEach { dep ->
            if (isGroovyDependency(dep.groupId, dep.artifactId)) {
                val version = resolveVersion(dep.version, model)
                if (version != null) {
                    logger.debug("Found Groovy version $version in dependencies")
                    return GroovyVersionInfo(
                        version = version,
                        majorMinor = extractMajorMinor(version),
                        source = "pom.xml",
                    )
                }
            }
        }

        // Check dependency management
        model.dependencyManagement?.dependencies?.forEach { dep ->
            if (isGroovyDependency(dep.groupId, dep.artifactId)) {
                val version = resolveVersion(dep.version, model)
                if (version != null) {
                    logger.debug("Found Groovy version $version in dependencyManagement")
                    return GroovyVersionInfo(
                        version = version,
                        majorMinor = extractMajorMinor(version),
                        source = "pom.xml",
                    )
                }
            }
        }

        // Check properties
        val groovyVersionProperty = model.properties?.getProperty("groovy.version")
        if (groovyVersionProperty != null) {
            logger.debug("Found groovy.version property: $groovyVersionProperty")
            return GroovyVersionInfo(
                version = groovyVersionProperty,
                majorMinor = extractMajorMinor(groovyVersionProperty),
                source = "pom.xml",
            )
        }

        return null
    }

    /**
     * Detects Groovy version from a Gradle build file.
     *
     * Supports both Groovy DSL (build.gradle) and Kotlin DSL (build.gradle.kts).
     * Looks for:
     * - implementation 'org.apache.groovy:groovy:VERSION'
     * - implementation("org.apache.groovy:groovy:VERSION")
     * - groovyVersion = 'VERSION' property
     *
     * @param buildFile Path to build.gradle or build.gradle.kts.
     * @return Groovy version info or null if not found.
     */
    fun detectFromGradle(buildFile: Path): GroovyVersionInfo? {
        if (!Files.exists(buildFile)) {
            logger.debug("Build file not found: $buildFile")
            return null
        }

        return try {
            val content = Files.readString(buildFile)
            val source = buildFile.fileName.toString()

            // Try to extract groovyVersion property first
            val propertyVersion = extractGroovyVersionProperty(content)
            if (propertyVersion != null) {
                logger.debug("Found groovyVersion property: $propertyVersion in $source")
                return GroovyVersionInfo(
                    version = propertyVersion,
                    majorMinor = extractMajorMinor(propertyVersion),
                    source = source,
                )
            }

            // Look for Groovy dependency declarations
            val version = extractGroovyDependencyVersion(content)
            if (version != null) {
                logger.debug("Found Groovy dependency version: $version in $source")
                return GroovyVersionInfo(
                    version = version,
                    majorMinor = extractMajorMinor(version),
                    source = source,
                )
            }

            null
        } catch (e: java.io.IOException) {
            logger.error("Failed to read Gradle build file: $buildFile", e)
            null
        }
    }

    /**
     * Checks if a Maven dependency is a Groovy artifact.
     */
    private fun isGroovyDependency(groupId: String?, artifactId: String?): Boolean {
        if (groupId == null || artifactId == null) return false

        val knownGroovyGroups = setOf("org.apache.groovy", "org.codehaus.groovy")
        val knownGroovyArtifacts = setOf("groovy", "groovy-all")

        return groupId in knownGroovyGroups && artifactId in knownGroovyArtifacts
    }

    /**
     * Resolves a Maven version, handling property references like ${groovy.version}.
     */
    private fun resolveVersion(version: String?, model: Model): String? {
        if (version == null) return null

        // Check if version is a property reference
        val propertyPattern = """\$\{(.+?)\}""".toRegex()
        val match = propertyPattern.find(version)
        if (match != null) {
            val propertyName = match.groupValues[1]
            return model.properties?.getProperty(propertyName)
        }

        return version
    }

    /**
     * Extracts major.minor version from a full version string.
     *
     * Examples:
     * - "4.0.15" -> "4.0"
     * - "3.0.9-beta-1" -> "3.0"
     * - "4" -> "4.0" (assumes .0 for single-part versions)
     * - "4.0" -> "4.0"
     */
    private fun extractMajorMinor(version: String): String {
        val parts = version.split(".", "-", limit = 3)
        return when {
            parts.size >= 2 -> "${parts[0]}.${parts[1]}"
            parts.isNotEmpty() -> "${parts[0]}.0" // Assume .0 for single-part versions
            else -> version
        }
    }

    /**
     * Extracts Groovy version from a groovyVersion property in Gradle build files.
     *
     * Examples:
     * - groovyVersion = '4.0.15'
     * - ext.groovyVersion = '4.0.15'
     * - val groovyVersion = "4.0.15"
     */
    private fun extractGroovyVersionProperty(content: String): String? {
        val patterns = listOf(
            """groovyVersion\s*=\s*['"]([^'"]+)['"]""".toRegex(),
            """ext\.groovyVersion\s*=\s*['"]([^'"]+)['"]""".toRegex(),
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extracts Groovy version from dependency declarations in Gradle build files.
     *
     * Examples:
     * - implementation 'org.apache.groovy:groovy:4.0.15'
     * - implementation "org.codehaus.groovy:groovy-all:3.0.9"
     * - implementation("org.apache.groovy:groovy:4.0.15")
     */
    private fun extractGroovyDependencyVersion(content: String): String? {
        // Common prefix for dependency configurations
        val configPrefix = """\b(?:implementation|api|compile|compileOnly|runtimeOnly)"""

        val patterns = listOf(
            // Groovy DSL with single quotes - anchored to dependency configuration
            """$configPrefix\s+['"]org\.apache\.groovy:groovy(?:-all)?:([^'"]+)['"]""".toRegex(),
            """$configPrefix\s+['"]org\.codehaus\.groovy:groovy(?:-all)?:([^'"]+)['"]""".toRegex(),
            // Kotlin DSL with parentheses - anchored to dependency configuration
            """$configPrefix\s*\(\s*"org\.apache\.groovy:groovy(?:-all)?:([^"]+)"\s*\)""".toRegex(),
            """$configPrefix\s*\(\s*"org\.codehaus\.groovy:groovy(?:-all)?:([^"]+)"\s*\)""".toRegex(),
        )

        for (pattern in patterns) {
            val match = pattern.find(content)
            if (match != null) {
                val version = match.groupValues[1]
                // Skip if it's a property reference like $groovyVersion or ${groovyVersion}
                if (!version.startsWith("$")) {
                    return version
                }
            }
        }

        return null
    }
}
