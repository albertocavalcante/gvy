package com.github.albertocavalcante.diagnostics.sarif

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SARIF 2.1.0 data models for static analysis results.
 *
 * @see <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/">SARIF 2.1.0 Specification</a>
 */

/**
 * Root SARIF output structure.
 */
@Serializable
data class SarifOutput(
    @SerialName("\$schema")
    val schema: String = SARIF_SCHEMA,
    val version: String = SARIF_VERSION,
    val runs: List<SarifRun>,
) {
    companion object {
        const val SARIF_VERSION = "2.1.0"
        const val SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
    }
}

/**
 * A run represents a single invocation of a static analysis tool.
 */
@Serializable
data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult> = emptyList(),
    val invocations: List<SarifInvocation>? = null,
)

/**
 * Tool information including driver and optional extensions.
 */
@Serializable
data class SarifTool(val driver: SarifDriver, val extensions: List<SarifDriver>? = null)

/**
 * Tool component (driver or extension) with rules and metadata.
 */
@Serializable
data class SarifDriver(
    val name: String,
    val version: String? = null,
    val informationUri: String? = null,
    val rules: List<SarifRule>? = null,
)

/**
 * A rule definition with metadata for documentation.
 */
@Serializable
data class SarifRule(
    val id: String,
    val name: String? = null,
    val shortDescription: SarifMessage? = null,
    val fullDescription: SarifMessage? = null,
    val helpUri: String? = null,
    val defaultConfiguration: SarifRuleConfiguration? = null,
    val properties: SarifRuleProperties? = null,
)

/**
 * Rule configuration defaults.
 */
@Serializable
data class SarifRuleConfiguration(val level: SarifLevel? = null, val enabled: Boolean? = null)

/**
 * Custom rule properties (e.g., CodeNarc priority).
 */
@Serializable
data class SarifRuleProperties(val priority: Int? = null, val category: String? = null, val tags: List<String>? = null)

/**
 * A single analysis result (finding/diagnostic).
 */
@Serializable
data class SarifResult(
    val ruleId: String,
    val level: SarifLevel = SarifLevel.WARNING,
    val message: SarifMessage,
    val locations: List<SarifLocation>? = null,
    val ruleIndex: Int? = null,
    val fingerprints: Map<String, String>? = null,
)

/**
 * SARIF severity levels.
 */
@Serializable
enum class SarifLevel {
    @SerialName("none")
    NONE,

    @SerialName("note")
    NOTE,

    @SerialName("warning")
    WARNING,

    @SerialName("error")
    ERROR,
}

/**
 * A message with text content.
 */
@Serializable
data class SarifMessage(val text: String, val markdown: String? = null)

/**
 * Location of a result in source code.
 */
@Serializable
data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation? = null,
    val logicalLocations: List<SarifLogicalLocation>? = null,
)

/**
 * Physical file location with artifact and region.
 */
@Serializable
data class SarifPhysicalLocation(val artifactLocation: SarifArtifactLocation, val region: SarifRegion? = null)

/**
 * Reference to an artifact (file).
 */
@Serializable
data class SarifArtifactLocation(val uri: String, val uriBaseId: String? = null, val index: Int? = null)

/**
 * Region within a file (line/column range).
 */
@Serializable
data class SarifRegion(
    val startLine: Int,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
)

/**
 * Logical location (e.g., class, method name).
 */
@Serializable
data class SarifLogicalLocation(
    val name: String? = null,
    val fullyQualifiedName: String? = null,
    val kind: String? = null,
)

/**
 * Information about a tool invocation.
 */
@Serializable
data class SarifInvocation(
    val executionSuccessful: Boolean,
    val commandLine: String? = null,
    val workingDirectory: SarifArtifactLocation? = null,
    val startTimeUtc: String? = null,
    val endTimeUtc: String? = null,
)
