package com.github.albertocavalcante.gvy.gls

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Properties

/**
 * Build provenance stamped into `version.properties` by the `generateVersionProperties` Gradle task.
 *
 * Pure data and parsing: no IO happens here, so every shape of properties file (full, legacy, empty) is testable.
 * Missing fields degrade to [UNKNOWN] rather than failing, so a source-tarball build still starts.
 */
data class BuildInfo(
    /** Legacy version string (`<base>-SNAPSHOT` for development builds). */
    val version: String,
    val baseVersion: String,
    /** Human-facing version: `<base>-dev+g<sha7>[.dirty]` for development builds, `<base>` for releases. */
    val displayVersion: String,
    val gitSha: String,
    val gitShaShort: String,
    /** `null` when the build could not tell (no git checkout). */
    val gitDirty: Boolean?,
    /** Short digest of the uncommitted changes; empty for a clean tree. */
    val gitDirtyHash: String,
    val gitBranch: String,
    val commitTime: Instant?,
    val buildTime: Instant?,
    val ciRunUrl: String?,
) {
    /** Whether the properties carried a commit, i.e. the build was stamped. */
    val isStamped: Boolean get() = gitSha != UNKNOWN

    private val dirtyDetail: String
        get() = gitDirtyHash.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

    /** One line for logs: version, commit and tree state. */
    fun startupLine(): String {
        val tree = when (gitDirty) {
            true -> "dirty$dirtyDetail"
            false -> "clean"
            null -> UNKNOWN
        }
        return "gls $displayVersion (git $gitSha, tree $tree, branch $gitBranch)"
    }

    /** Human-readable multi-line report; the first line stays `gls version <token>` for scripts. */
    fun report(): String = buildString {
        appendLine("gls version $displayVersion")
        appendLine("  commit:      $gitSha")
        appendLine("  branch:      $gitBranch")
        appendLine("  dirty:       ${gitDirty ?: UNKNOWN}$dirtyDetail")
        appendLine("  commit time: ${commitTime ?: UNKNOWN}")
        appendLine("  build time:  ${buildTime ?: UNKNOWN}")
        ciRunUrl?.let { appendLine("  ci run:      $it") }
    }.trimEnd()

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "version" to JsonPrimitive(displayVersion),
            "baseVersion" to JsonPrimitive(baseVersion),
            "legacyVersion" to JsonPrimitive(version),
            "gitSha" to JsonPrimitive(gitSha),
            "gitShaShort" to JsonPrimitive(gitShaShort),
            "gitDirty" to (gitDirty?.let(::JsonPrimitive) ?: JsonNull),
            "gitDirtyHash" to JsonPrimitive(gitDirtyHash),
            "gitBranch" to JsonPrimitive(gitBranch),
            "commitTime" to (commitTime?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            "buildTime" to (buildTime?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            "ciRunUrl" to (ciRunUrl?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    companion object {
        const val UNKNOWN = "unknown"

        fun fromProperties(properties: Properties): BuildInfo {
            fun text(key: String) = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            val version = text("version") ?: UNKNOWN
            return BuildInfo(
                version = version,
                baseVersion = text("baseVersion") ?: UNKNOWN,
                // Pre-provenance jars only carry `version`.
                displayVersion = text("displayVersion") ?: version,
                gitSha = text("gitSha") ?: UNKNOWN,
                gitShaShort = text("gitShaShort") ?: UNKNOWN,
                gitDirty = text("gitDirty")?.toBooleanStrictOrNull(),
                gitDirtyHash = text("gitDirtyHash").orEmpty(),
                gitBranch = text("gitBranch") ?: UNKNOWN,
                commitTime = parseInstant(text("commitTime")),
                buildTime = parseInstant(text("buildTime")),
                ciRunUrl = text("ciRunUrl"),
            )
        }

        private fun parseInstant(value: String?): Instant? = try {
            value?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
