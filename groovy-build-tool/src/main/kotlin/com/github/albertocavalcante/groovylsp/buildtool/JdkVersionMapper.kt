package com.github.albertocavalcante.groovylsp.buildtool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

/**
 * Maps between JDK versions and class file major versions.
 *
 * The relationship is: majorVersion = jdkVersion + 44
 * For example:
 * - JDK 8 -> major version 52
 * - JDK 21 -> major version 65
 * - JDK 25 -> major version 69
 */
object JdkVersionMapper {

    private val logger = KotlinLogging.logger {}

    @Serializable
    private data class CompatibilityMatrix(val majorVersionMapping: Map<String, Int> = emptyMap())

    // Load the mapping from the JSON resource
    private val majorVersionToJdk: Map<Int, Int> by lazy {
        loadMajorVersionMapping()
    }

    private fun loadMajorVersionMapping(): Map<Int, Int> = runCatching {
        val resourceStream = checkNotNull(
            JdkVersionMapper::class.java.getResourceAsStream("/groovy-compatibility.json"),
        ) {
            "groovy-compatibility.json not found in resources"
        }

        val content = InputStreamReader(resourceStream).use { it.readText() }
        val json = Json { ignoreUnknownKeys = true }
        val matrix = json.decodeFromString<CompatibilityMatrix>(content)

        matrix.majorVersionMapping.mapKeys { it.key.toInt() }
    }.getOrElse { throwable ->
        if (throwable is Error) throw throwable
        logger.error(throwable) { "Failed to load major version mapping from groovy-compatibility.json" }
        emptyMap()
    }

    /**
     * Maps a class file major version to its corresponding JDK version.
     * Returns null if the major version is unknown.
     *
     * @param majorVersion The class file major version (e.g., 69)
     * @return The JDK version (e.g., 25) or null if unknown
     */
    fun toJdkVersion(majorVersion: Int): Int? = majorVersionToJdk[majorVersion]

    /**
     * Maps a JDK version to its corresponding class file major version.
     * Uses the formula: majorVersion = jdkVersion + 44
     *
     * @param jdkVersion The JDK version (e.g., 25)
     * @return The class file major version (e.g., 69)
     */
    fun toMajorVersion(jdkVersion: Int): Int = jdkVersion + 44

    /**
     * Parses the major version from an ASM error message.
     * Expected format: "Unsupported class file major version 69"
     *
     * @param errorMessage The error message
     * @return The major version number or null if not found/parseable
     */
    fun parseMajorVersionFromError(errorMessage: String?): Int? {
        if (errorMessage.isNullOrBlank()) return null

        // Regex to match "major version <number>"
        val regex = Regex("""major\s+version\s+(\d+)""", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(errorMessage) ?: return null

        return matchResult.groupValues.getOrNull(1)?.toIntOrNull()
    }
}
