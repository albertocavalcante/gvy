package com.github.albertocavalcante.groovyjenkins.metadata.extracted

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-plugin extracted metadata from Jenkins GDSL.
 * Each plugin gets its own JSON file for better maintainability.
 *
 * This is Tier 1 metadata - machine-generated, deterministic,
 * and reproducible from a specific Jenkins version + plugin combination.
 */
@Serializable
data class PluginMetadata(
    @SerialName("\$schema")
    val schema: String = "https://groovy-lsp.dev/schemas/jenkins-plugin-metadata-v1.json",
    val plugin: PluginInfo,
    val extraction: ExtractionInfo,
    val steps: Map<String, ExtractedStep>,
    val globalVariables: Map<String, ExtractedGlobalVariable> = emptyMap(),
)

/**
 * Information about the plugin that provides this metadata.
 */
@Serializable
data class PluginInfo(
    val id: String, // e.g., "workflow-basic-steps" or "core" for Jenkins core
    val version: String, // e.g., "1058.vcb_fc1e3a_21a_9"
    val displayName: String? = null,
)

/**
 * Metadata about the extraction process itself.
 * Provides full auditability and reproducibility.
 */
@Serializable
data class ExtractionInfo(
    val jenkinsVersion: String, // e.g., "2.426.3"
    val extractedAt: String, // ISO 8601 timestamp
    val pluginsManifestSha256: String, // SHA-256 of plugins.txt
    val gdslSha256: String, // SHA-256 of extracted GDSL file
    val extractorVersion: String = "1.0.0", // Version of the extraction tool
)

/**
 * A pipeline step extracted from GDSL.
 */
@Serializable
data class ExtractedStep(
    val scope: StepScope,
    val positionalParams: List<String> = emptyList(),
    val namedParams: Map<String, ExtractedParameter>,
    val documentation: String? = null,
    val returnType: String? = null,
)

/**
 * The execution scope where a step can be used.
 */
@Serializable
enum class StepScope {
    @SerialName("global")
    GLOBAL, // Can be used anywhere

    @SerialName("node")
    NODE, // Requires node {} block

    @SerialName("stage")
    STAGE, // Requires stage {} block
}

/**
 * A parameter for a pipeline step.
 */
@Serializable
data class ExtractedParameter(
    val type: String, // Simplified type name (e.g., "String", "boolean", "Map")
    val defaultValue: String? = null,
)

/**
 * A global variable available in Jenkinsfiles (e.g., env, params, currentBuild).
 */
@Serializable
data class ExtractedGlobalVariable(
    val type: String, // Fully qualified type name
    val documentation: String? = null,
)
