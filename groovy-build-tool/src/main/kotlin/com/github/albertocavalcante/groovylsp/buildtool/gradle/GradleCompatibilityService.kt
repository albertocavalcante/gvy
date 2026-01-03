package com.github.albertocavalcante.groovylsp.buildtool.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.util.GradleVersion
import org.slf4j.LoggerFactory
import java.io.InputStreamReader

@Serializable
private data class CompatibilityEntry(val java: String, val gradle: String)

@Serializable
private data class CompatibilityMatrix(val compatibility: List<CompatibilityEntry>)

class GradleCompatibilityService {

    private val logger = LoggerFactory.getLogger(GradleCompatibilityService::class.java)

    // Maps JDK Major Version -> Minimum Gradle Version Requirement
    private val minimumGradleVersions: Map<Int, GradleVersion> by lazy {
        loadCompatibilityMatrix()
    }

    private fun loadCompatibilityMatrix(): Map<Int, GradleVersion> = try {
        val resourceStream = javaClass.getResourceAsStream("/gradle-compatibility.json")
            ?: throw IllegalStateException("Detailed gradle-compatibility.json not found in resources")

        val content = InputStreamReader(resourceStream).use { it.readText() }
        val matrix = Json.decodeFromString<CompatibilityMatrix>(content)

        matrix.compatibility.associate { entry ->
            val jdkVersion = entry.java.toInt()
            val minGradle = parseMinGradleVersion(entry.gradle)
            jdkVersion to minGradle
        }
    } catch (e: Exception) {
        logger.error("Failed to load Gradle compatibility matrix", e)
        emptyMap()
    }

    private fun parseMinGradleVersion(versionSpec: String): GradleVersion {
        // Handle "8.5+" -> "8.5"
        val cleanVersion = versionSpec.removeSuffix("+").trim()
        return GradleVersion.version(cleanVersion)
    }

    /**
     * Checks if the given Gradle version is compatible with the specified JDK major version.
     */
    fun isCompatible(gradleVersion: String, jdkVersion: Int): Boolean {
        val minRequired = minimumGradleVersions[jdkVersion] ?: return true // Unknown JDK, assume compatible
        val current = parseGradleVersionSafe(gradleVersion) ?: return true // Can't parse gradle version, fail open

        return current >= minRequired
    }

    /**
     * Generates a fix suggestion if incompatible, or null if compatible.
     */
    fun suggestFix(gradleVersion: String, jdkVersion: Int): String? {
        if (isCompatible(gradleVersion, jdkVersion)) return null

        val minRequired = minimumGradleVersions[jdkVersion]?.version
        return "Gradle $gradleVersion is not compatible with JDK $jdkVersion. " +
            "Please upgrade to Gradle $minRequired+ or run the LSP with an older JDK."
    }

    /**
     * Returns the minimum Gradle version string required for the given JDK, or null if unknown.
     */
    fun getMinimumGradleVersion(jdkVersion: Int): String? = minimumGradleVersions[jdkVersion]?.version

    private fun parseGradleVersionSafe(version: String): GradleVersion? = try {
        GradleVersion.version(version)
    } catch (e: Exception) {
        logger.warn("Failed to parse Gradle version: $version", e)
        null
    }
}
