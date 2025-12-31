package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.json.MetadataJson
import com.github.albertocavalcante.groovyjenkins.metadata.json.toBundledMetadata
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads Jenkins metadata from a static JSON file.
 *
 * This allows users to provide pre-generated metadata (e.g. from jenkins-static-extractor)
 * to supplement or replace dynamic extraction/bundled metadata.
 */
class JsonMetadataLoader {
    private val logger = LoggerFactory.getLogger(JsonMetadataLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Load metadata from the specified file.
     *
     * @param path Path to the JSON metadata file
     * @return Parsed metadata
     * @throws IllegalStateException if file not found or invalid
     */
    fun load(path: Path): BundledJenkinsMetadata {
        logger.debug("Loading Jenkins metadata from {}", path)

        if (!Files.exists(path)) {
            throw IllegalStateException("Jenkins metadata file not found: $path")
        }

        return try {
            val jsonString = Files.readString(path)
            val metadataJson = json.decodeFromString<MetadataJson>(jsonString)
            metadataJson.toBundledMetadata()
        } catch (e: Exception) {
            logger.error("Failed to load Jenkins metadata from $path", e)
            throw IllegalStateException("Failed to parse Jenkins metadata: ${e.message}", e)
        }
    }
}
