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
 * Result of a compatibility check between a Groovy version and a JDK.
 *
 * "No data" is its own variant, [Unknown], so it cannot be read as compatible. Handle the variants with an
 * exhaustive `when`; [message] is the one human-readable rendering of each variant.
 */
sealed interface CompatibilityResult {
    /** Human-readable explanation of the compatibility status. */
    val message: String

    /** The Groovy [line] (e.g. "5.0") is fully supported on [jdkVersion]. */
    data class Compatible(val line: String, val jdkVersion: Int) : CompatibilityResult {
        override val message: String get() = "Groovy $line is fully compatible with JDK $jdkVersion"
    }

    /**
     * The Groovy [line] has partial or experimental support on [jdkVersion]; full support ends at
     * [fullySupportedUpToJdk].
     */
    data class PartiallySupported(val line: String, val jdkVersion: Int, val fullySupportedUpToJdk: Int) :
        CompatibilityResult {
        override val message: String
            get() = "Groovy $line has partial/experimental support for JDK $jdkVersion " +
                "(fully supports up to JDK $fullySupportedUpToJdk)"
    }

    /** The Groovy [line] is known not to support [jdkVersion]. */
    sealed interface Incompatible : CompatibilityResult {
        val line: String
        val jdkVersion: Int

        /** [jdkVersion] is older than the oldest JDK [minimumJdk] the Groovy [line] supports. */
        data class BelowMinimum(override val line: String, override val jdkVersion: Int, val minimumJdk: Int) :
            Incompatible {
            override val message: String
                get() = "Groovy $line requires JDK $minimumJdk or later, but JDK $jdkVersion is being used"
        }

        /** [jdkVersion] is newer than the newest JDK [maxSupportedJdk] the Groovy [line] supports, even partially. */
        data class AboveMaximum(override val line: String, override val jdkVersion: Int, val maxSupportedJdk: Int) :
            Incompatible {
            override val message: String
                get() = "Groovy $line is not compatible with JDK $jdkVersion (max supported: JDK $maxSupportedJdk)"
        }
    }

    /**
     * There is no compatibility data for [groovyVersion], exactly as it was asked about: its line is not in the
     * table, it could not be parsed, or the table failed to load. This says nothing about compatibility.
     */
    data class Unknown(val groovyVersion: String) : CompatibilityResult {
        override val message: String get() = "Unknown Groovy version $groovyVersion - no compatibility data"
    }
}

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
     * @return the matching [CompatibilityResult] variant; [CompatibilityResult.Unknown] when the version's line is
     *   not in the table (never matched to a neighbouring line), cannot be parsed, or the table failed to load
     */
    fun checkCompatibility(groovyVersion: String, jdkVersion: Int): CompatibilityResult {
        val line = parseGroovyMajorVersion(groovyVersion)
        val entry = line?.let { wanted -> compatibilityEntries.find { it.groovy == wanted } }
            ?: return CompatibilityResult.Unknown(groovyVersion)
        val partialMax = entry.jdkMaxPartial

        return when {
            jdkVersion < entry.jdkMin -> CompatibilityResult.Incompatible.BelowMinimum(
                entry.groovy,
                jdkVersion,
                entry.jdkMin,
            )
            jdkVersion <= entry.jdkMax -> CompatibilityResult.Compatible(entry.groovy, jdkVersion)
            partialMax != null && jdkVersion <= partialMax ->
                CompatibilityResult.PartiallySupported(entry.groovy, jdkVersion, entry.jdkMax)
            else -> CompatibilityResult.Incompatible.AboveMaximum(entry.groovy, jdkVersion, partialMax ?: entry.jdkMax)
        }
    }

    /**
     * Suggests the newest Groovy line that fully supports the given JDK version.
     *
     * Partial or experimental support (`jdkMaxPartial`) does not count, and the result does not depend on the
     * order of the entries in the compatibility file.
     *
     * @param jdkVersion The JDK major version (e.g., 25)
     * @return a suggestion such as "Groovy 6.0", or `null` when no line in the table fully supports the JDK (older or
     *   newer than every line) or the table failed to load. Callers treat both as "no suggestion", so they are not
     *   told apart; a failed load is logged at error level by [loadBundledCompatibilityEntries].
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
