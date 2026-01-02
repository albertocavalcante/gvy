@file:Suppress(
    "TooGenericExceptionCaught", // JSON parsing uses catch-all for resilience
    "LongMethod", // toBundledMetadata transforms all metadata types
    "UseCheckOrError", // Explicit IllegalStateException provides clearer error messages
)

package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.json.MetadataJson
import com.github.albertocavalcante.groovyjenkins.metadata.json.toBundledMetadata
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Loads bundled Jenkins metadata from resources.
 *
 * This loader reads the `jenkins-stubs-metadata.json` file bundled with the LSP,
 * providing immediate Jenkins step completions without requiring user configuration.
 *
 * Supports:
 * - Pipeline steps (sh, echo, git, etc.)
 * - Global variables (env, params, currentBuild, etc.)
 * - Post build conditions (always, success, failure, etc.)
 * - Declarative options (timestamps, disableConcurrentBuilds, etc.)
 * - Agent types (any, none, label, docker, etc.)
 *
 * @see BundledJenkinsMetadata
 */
class BundledJenkinsMetadataLoader {
    private val logger = LoggerFactory.getLogger(BundledJenkinsMetadataLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val METADATA_RESOURCE = "/jenkins-stubs-metadata.json"
    }

    /**
     * Load bundled Jenkins metadata from resources.
     *
     * @return Parsed metadata
     * @throws IllegalStateException if metadata resource not found or invalid
     */
    fun load(): BundledJenkinsMetadata {
        logger.debug("Loading bundled Jenkins metadata from {}", METADATA_RESOURCE)

        val resourceStream = javaClass.getResourceAsStream(METADATA_RESOURCE)
            ?: throw IllegalStateException("Bundled Jenkins metadata not found: $METADATA_RESOURCE")

        return try {
            val jsonString = resourceStream.bufferedReader().use { it.readText() }
            val metadataJson = json.decodeFromString<MetadataJson>(jsonString)
            metadataJson.toBundledMetadata()
        } catch (e: Exception) {
            logger.error("Failed to load bundled Jenkins metadata", e)
            throw IllegalStateException("Failed to parse bundled Jenkins metadata: ${e.message}", e)
        }
    }
}
