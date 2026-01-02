package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.DeprecationInfo
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.DirectiveEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.PropertyEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.SectionEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.StepCategory
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope

/**
 * Runtime metadata model combining extracted (Tier 1) and enriched (Tier 2) data.
 *
 * This is the primary data structure used by all LSP features:
 * - Code completion
 * - Hover documentation
 * - Signature help
 * - Diagnostics
 *
 * The merged model provides the best available information from both tiers,
 * with enriched data taking precedence when available.
 */
data class MergedJenkinsMetadata(
    val jenkinsVersion: String,
    val steps: Map<String, MergedStepMetadata>,
    val globalVariables: Map<String, MergedGlobalVariable>,
    val sections: Map<String, SectionEnrichment>,
    val directives: Map<String, DirectiveEnrichment>,
    val declarativeOptions: Map<String, MergedDeclarativeOption> = emptyMap(),
) {
    /**
     * Retrieve step metadata by name.
     * @return The step metadata, or null if not found
     */
    fun getStep(name: String): MergedStepMetadata? = steps[name]

    /**
     * Retrieve global variable metadata by name.
     * @return The variable metadata, or null if not found
     */
    fun getGlobalVariable(name: String): MergedGlobalVariable? = globalVariables[name]

    /**
     * Retrieve declarative option metadata by name.
     * @return The option metadata, or null if not found
     */
    fun getDeclarativeOption(name: String): MergedDeclarativeOption? = declarativeOptions[name]
}

/**
 * Merged metadata for a pipeline step.
 * Combines machine-extracted data with human-curated enrichment.
 */
data class MergedStepMetadata(
    // From Tier 1 (extracted) - always present
    val name: String,
    val scope: StepScope,
    val positionalParams: List<String>,
    val namedParams: Map<String, MergedParameter>,
    val extractedDocumentation: String?,
    val returnType: String?,

    // From Tier 2 (enrichment) - may be null if no enrichment exists
    val plugin: String?,
    val enrichedDescription: String?,
    val documentationUrl: String?,
    val category: StepCategory?,
    val examples: List<String>,
    val deprecation: DeprecationInfo?,
) {
    /**
     * Best available documentation: enriched description takes precedence,
     * falling back to extracted documentation if enrichment is unavailable.
     */
    val documentation: String?
        get() = enrichedDescription ?: extractedDocumentation
}

/**
 * Merged metadata for a step parameter.
 * Combines type information from extraction with validation rules from enrichment.
 */
data class MergedParameter(
    val name: String,
    val type: String,
    val defaultValue: String?,

    // From enrichment
    val description: String?,
    val required: Boolean = false, // Default to false if enrichment doesn't specify
    val validValues: List<String>?,
    val examples: List<String>,
)

/**
 * Merged metadata for declarative pipeline options (e.g., disableConcurrentBuilds).
 */
data class MergedDeclarativeOption(
    val name: String,
    val plugin: String,
    val parameters: Map<String, MergedParameter>,
    val documentation: String?,
)

/**
 * Merged metadata for a global variable (env, params, currentBuild, etc.).
 */
data class MergedGlobalVariable(
    val name: String,
    val type: String,
    val extractedDocumentation: String?,

    // From enrichment
    val enrichedDescription: String?,
    val documentationUrl: String?,
    val properties: Map<String, PropertyEnrichment>,
) {
    /**
     * Best available documentation: enriched description takes precedence,
     * falling back to extracted documentation.
     */
    val documentation: String?
        get() = enrichedDescription ?: extractedDocumentation
}
