package com.github.albertocavalcante.groovyjenkins.metadata.enrichment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tier 2 metadata: Hand-curated enrichment data for Jenkins steps and features.
 *
 * This file is version-controlled and maintained by humans to provide
 * better documentation, examples, and categorization beyond what can be
 * automatically extracted from Jenkins.
 *
 * TODO: Align all Jenkins metadata schemas to date-based versioning (YYYY-MM-DD) for easier evolution.
 */
@Serializable
data class JenkinsEnrichment(
    @SerialName("\$schema")
    val schema: String = "https://groovy-lsp.dev/schemas/jenkins-enrichment-2025-12-21.json",
    val version: String = "1.0.0",
    val steps: Map<String, StepEnrichment> = emptyMap(),
    val globalVariables: Map<String, GlobalVariableEnrichment> = emptyMap(),
    val sections: Map<String, SectionEnrichment> = emptyMap(),
    val directives: Map<String, DirectiveEnrichment> = emptyMap(),
)

/**
 * Enrichment data for a pipeline step.
 * Complements the extracted metadata with human-curated information.
 */
@Serializable
data class StepEnrichment(
    val plugin: String, // Plugin that provides this step
    val description: String? = null,
    val documentationUrl: String? = null,
    val category: StepCategory? = null,
    val examples: List<String> = emptyList(),
    val parameterEnrichment: Map<String, ParameterEnrichment> = emptyMap(),
    val deprecation: DeprecationInfo? = null,
)

/**
 * Categories for organizing pipeline steps.
 */
@Serializable
enum class StepCategory {
    @SerialName("scm")
    SCM, // Source control management

    @SerialName("build")
    BUILD, // Build and compilation

    @SerialName("test")
    TEST, // Testing and quality

    @SerialName("deploy")
    DEPLOY, // Deployment and release

    @SerialName("notification")
    NOTIFICATION, // Notifications and alerts

    @SerialName("utility")
    UTILITY, // General utilities
}

/**
 * Additional information about a step parameter.
 */
@Serializable
data class ParameterEnrichment(
    val description: String? = null,
    val required: Boolean? = null,
    val validValues: List<String>? = null,
    val examples: List<String> = emptyList(),
)

/**
 * Information about deprecated features.
 */
@Serializable
data class DeprecationInfo(
    val since: String, // Version when deprecated
    val replacement: String? = null, // Recommended replacement
    val message: String, // Deprecation message
)

/**
 * Enrichment for global variables like env, params, currentBuild.
 */
@Serializable
data class GlobalVariableEnrichment(
    val description: String,
    val documentationUrl: String? = null,
    val properties: Map<String, PropertyEnrichment> = emptyMap(),
)

/**
 * Information about a property on a global variable.
 */
@Serializable
data class PropertyEnrichment(val type: String, val description: String, val readOnly: Boolean = false)

/**
 * Declarative pipeline sections (agent, stages, steps, post).
 */
@Serializable
data class SectionEnrichment(
    val description: String,
    val allowedIn: List<String>, // Where this section can appear
    val innerInstructions: List<String> = emptyList(), // Valid nested instructions
    val documentationUrl: String? = null,
)

/**
 * Declarative pipeline directives (environment, options, parameters, triggers).
 */
@Serializable
data class DirectiveEnrichment(
    val description: String,
    val allowedIn: List<String>,
    val documentationUrl: String? = null,
)
