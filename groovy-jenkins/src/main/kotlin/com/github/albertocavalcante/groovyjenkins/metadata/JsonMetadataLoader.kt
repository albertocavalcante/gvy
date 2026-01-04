package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.json.MetadataJson
import com.github.albertocavalcante.groovyjenkins.metadata.json.toBundledMetadata
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
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

        check(Files.exists(path)) { "Jenkins metadata file not found: $path" }

        return runCatching {
            val jsonString = Files.readString(path)
            val metadataJson = json.decodeFromString<MetadataJson>(jsonString)
            metadataJson.toBundledMetadata()
        }.getOrElse { throwable ->
            if (throwable is Error) throw throwable

            val wrapped = when (throwable) {
                is IOException,
                is SerializationException,
                is IllegalArgumentException,
                -> IllegalStateException("Failed to parse Jenkins metadata: ${throwable.message}", throwable)
                else -> IllegalStateException("Failed to load Jenkins metadata: ${throwable.message}", throwable)
            }

            logger.error("Failed to load Jenkins metadata from $path", wrapped)
            throw wrapped
        }
    }
}
