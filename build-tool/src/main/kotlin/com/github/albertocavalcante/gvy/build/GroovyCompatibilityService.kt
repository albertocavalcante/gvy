// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

/**
 * Represents a single entry in the Groovy/JDK compatibility matrix.
 */
@Serializable
internal data class CompatibilityEntry(
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

private val logger = KotlinLogging.logger {}

/**
 * Reads the entries of the bundled `groovy-compatibility.json`, or an empty list (logged) when it cannot be read.
 */
internal fun loadBundledCompatibilityEntries(): List<CompatibilityEntry> = runCatching {
    val resourceStream = checkNotNull(
        GroovyCompatibilityService::class.java.getResourceAsStream("/groovy-compatibility.json"),
    ) {
        "groovy-compatibility.json not found in resources"
    }

    val content = InputStreamReader(resourceStream).use { it.readText() }
    Json.decodeFromString<CompatibilityMatrix>(content).compatibility
}.getOrElse { throwable ->
    if (throwable is Error) throw throwable
    logger.error(throwable) { "Failed to load Groovy compatibility matrix" }
    emptyList()
}

/**
 * Service for checking Groovy/JDK version compatibility.
 *
 * Loads compatibility information from groovy-compatibility.json and provides
 * methods to check if a given Groovy version works with a specific JDK version.
 */
class GroovyCompatibilityService internal constructor(private val entriesSource: () -> List<CompatibilityEntry>) {

    /** Creates a service backed by the bundled `groovy-compatibility.json`. */
    constructor() : this(::loadBundledCompatibilityEntries)

    // Entries ordered newest Groovy line first, whatever the order of the source.
    private val compatibilityEntries: List<CompatibilityEntry> by lazy {
        entriesSource().sortedWith(NEWEST_LINE_FIRST)
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
     * Suggests the newest Groovy line that fully supports the given JDK version.
     *
     * Partial or experimental support (`jdkMaxPartial`) does not count, and the result does not depend on the
     * order of the entries in the compatibility file.
     *
     * @param jdkVersion The JDK major version (e.g., 25)
     * @return A suggested Groovy version string, or null if no specific suggestion
     */
    fun suggestGroovyVersion(jdkVersion: Int): String? {
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

    private companion object {
        private val NEWEST_LINE_FIRST: Comparator<CompatibilityEntry> =
            compareByDescending<CompatibilityEntry> { lineNumber(it.groovy, 0) }
                .thenByDescending { lineNumber(it.groovy, 1) }

        /** Reads one dot-separated part of a "major.minor" line; unparseable parts sort oldest. */
        private fun lineNumber(line: String, index: Int): Int = line.split(".").getOrNull(index)?.toIntOrNull() ?: -1
    }
}
