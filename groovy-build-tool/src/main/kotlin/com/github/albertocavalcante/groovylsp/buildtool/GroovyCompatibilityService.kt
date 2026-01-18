package com.github.albertocavalcante.groovylsp.buildtool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

/**
 * Represents a single entry in the Groovy/JDK compatibility matrix.
 */
@Serializable
private data class CompatibilityEntry(
    val groovy: String,
    val jdkMin: Int,
    val jdkMax: Int,
    val jdkMaxPartial: Int? = null,
)

/**
 * Container for the full compatibility matrix loaded from JSON.
 */
@Serializable
private data class CompatibilityMatrix(
    val compatibility: List<CompatibilityEntry>,
    val majorVersionMapping: Map<String, Int> = emptyMap(),
)

/**
 * Result of a compatibility check.
 *
 * @property isFullyCompatible True if the Groovy version fully supports the JDK version
 * @property isPartiallyCompatible True if the Groovy version has partial/experimental support
 * @property message Human-readable explanation of the compatibility status
 */
data class CompatibilityResult(val isFullyCompatible: Boolean, val isPartiallyCompatible: Boolean, val message: String)

/**
 * Service for checking Groovy/JDK version compatibility.
 *
 * Loads compatibility information from groovy-compatibility.json and provides
 * methods to check if a given Groovy version works with a specific JDK version.
 */
class GroovyCompatibilityService {

    private val logger = KotlinLogging.logger {}

    // Sorted list of compatibility entries (loaded from JSON)
    private val compatibilityEntries: List<CompatibilityEntry> by lazy {
        loadCompatibilityMatrix()
    }

    private fun loadCompatibilityMatrix(): List<CompatibilityEntry> = runCatching {
        val resourceStream = checkNotNull(javaClass.getResourceAsStream("/groovy-compatibility.json")) {
            "groovy-compatibility.json not found in resources"
        }

        val content = InputStreamReader(resourceStream).use { it.readText() }
        val matrix = Json.decodeFromString<CompatibilityMatrix>(content)

        matrix.compatibility
    }.getOrElse { throwable ->
        if (throwable is Error) throw throwable
        logger.error(throwable) { "Failed to load Groovy compatibility matrix" }
        emptyList()
    }

    /**
     * Checks if the given Groovy version is compatible with the specified JDK version.
     *
     * @param groovyVersion The Groovy version (e.g., "4.0.22", "5.0.0")
     * @param jdkVersion The JDK major version (e.g., 21, 25)
     * @return CompatibilityResult with compatibility status and message
     */
    fun checkCompatibility(groovyVersion: String, jdkVersion: Int): CompatibilityResult {
        val groovyMajor = parseGroovyMajorVersion(groovyVersion)

        // Fail open: if we can't parse the version or don't have data, assume compatible
        if (groovyMajor == null) {
            return CompatibilityResult(
                isFullyCompatible = true,
                isPartiallyCompatible = false,
                message = "Unknown Groovy version $groovyVersion - assuming compatible",
            )
        }

        // Find the matching entry in the compatibility matrix
        val entry = compatibilityEntries.find { it.groovy == groovyMajor }

        // Fail open: if version not in matrix, assume compatible
        if (entry == null) {
            return CompatibilityResult(
                isFullyCompatible = true,
                isPartiallyCompatible = false,
                message = "Unknown Groovy version $groovyMajor - assuming compatible",
            )
        }

        return when {
            jdkVersion < entry.jdkMin -> {
                val message = "Groovy $groovyMajor requires JDK ${entry.jdkMin} or later, " +
                    "but JDK $jdkVersion is being used"
                CompatibilityResult(
                    isFullyCompatible = false,
                    isPartiallyCompatible = false,
                    message = message,
                )
            }

            jdkVersion <= entry.jdkMax -> {
                CompatibilityResult(
                    isFullyCompatible = true,
                    isPartiallyCompatible = false,
                    message = "Groovy $groovyMajor is fully compatible with JDK $jdkVersion",
                )
            }

            entry.jdkMaxPartial != null && jdkVersion <= entry.jdkMaxPartial -> {
                val message = "Groovy $groovyMajor has partial/experimental support for JDK $jdkVersion " +
                    "(fully supports up to JDK ${entry.jdkMax})"
                CompatibilityResult(
                    isFullyCompatible = false,
                    isPartiallyCompatible = true,
                    message = message,
                )
            }

            else -> {
                val maxSupported = entry.jdkMaxPartial ?: entry.jdkMax
                val message = "Groovy $groovyMajor is not compatible with JDK $jdkVersion " +
                    "(max supported: JDK $maxSupported)"
                CompatibilityResult(
                    isFullyCompatible = false,
                    isPartiallyCompatible = false,
                    message = message,
                )
            }
        }
    }

    /**
     * Suggests a Groovy version that is compatible with the given JDK version.
     *
     * @param jdkVersion The JDK major version (e.g., 25)
     * @return A suggested Groovy version string, or null if no specific suggestion
     */
    fun suggestGroovyVersion(jdkVersion: Int): String? {
        // Find the first (highest) Groovy version that fully supports this JDK
        val compatibleEntry = compatibilityEntries.firstOrNull { entry ->
            jdkVersion >= entry.jdkMin && jdkVersion <= entry.jdkMax
        }

        return compatibleEntry?.let { "Groovy ${it.groovy}" }
    }

    /**
     * Parses the major version from a Groovy version string.
     * Examples:
     * - "5.0.0" -> "5.0"
     * - "4.0.22" -> "4.0"
     * - "3.0.0-rc-1" -> "3.0"
     * - "2.5.8" -> "2.5"
     * - "4" -> "4.0" (assumes .0 for single-part versions)
     */
    private fun parseGroovyMajorVersion(version: String): String? = runCatching {
        // Remove any suffix like -rc-1, -SNAPSHOT, etc.
        val cleanVersion = version.split("-").first()
        val parts = cleanVersion.split(".")

        when {
            parts.isEmpty() -> null
            parts.size == 1 -> "${parts[0]}.0" // Assume .0 for single-part versions
            // For 2.4.x and 2.5.x, keep both parts
            parts[0] == "2" && (parts[1] == "4" || parts[1] == "5") -> "${parts[0]}.${parts[1]}"
            // For all others (3.0.x, 4.0.x, 5.0.x), use major.minor
            else -> "${parts[0]}.${parts[1]}"
        }
    }.getOrNull()
}
