@file:Suppress(
    "TooGenericExceptionCaught", // JSON parsing uses catch-all for resilience
)

package com.github.albertocavalcante.groovyjenkins.metadata.enrichment

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Loads enrichment metadata from bundled resources.
 *
 * This loader reads the `jenkins-enrichment.json` file bundled with the LSP,
 * providing human-curated metadata that enriches the machine-extracted metadata
 * with better documentation, examples, valid values, and categorization.
 *
 * The enrichment metadata includes:
 * - Step enrichment (descriptions, examples, parameter details, valid values)
 * - Global variable enrichment (env, params, currentBuild, scm)
 * - Section enrichment (pipeline, agent, stages, stage, steps, post)
 * - Directive enrichment (environment, options, parameters, when, tools)
 *
 * @see JenkinsEnrichment
 * @see BundledJenkinsMetadataLoader (pattern to follow)
 */
class EnrichmentMetadataLoader {
    private val logger = LoggerFactory.getLogger(EnrichmentMetadataLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val ENRICHMENT_RESOURCE = "/jenkins-enrichment.json"
    }

    /**
     * Load enrichment metadata from resources.
     *
     * @return Parsed enrichment metadata
     * @throws IllegalStateException if enrichment resource not found or invalid
     */
    fun load(): JenkinsEnrichment {
        logger.debug("Loading enrichment metadata from {}", ENRICHMENT_RESOURCE)

        val resourceStream = javaClass.getResourceAsStream(ENRICHMENT_RESOURCE)
            ?: throw IllegalStateException("Enrichment metadata not found: $ENRICHMENT_RESOURCE")

        return try {
            val jsonString = resourceStream.bufferedReader().use { it.readText() }
            json.decodeFromString<JenkinsEnrichment>(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to load enrichment metadata", e)
            throw IllegalStateException("Failed to parse enrichment metadata: ${e.message}", e)
        }
    }
}
