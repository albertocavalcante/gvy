package com.github.albertocavalcante.groovyjenkins.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Loads bundled Jenkins metadata from resources.
 *
 * This loader reads the `jenkins-stubs-metadata.json` file bundled with the LSP,
 * providing immediate Jenkins step completions without requiring user configuration.
 *
 * Phase 0: Bundle Jenkins SDK stubs for top 10-20 plugins
 * TODO: Currently only includes minimal stubs (sh step). Needs expansion.
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

    /**
     * JSON structure for metadata file.
     *
     * Uses kotlinx.serialization for type-safe, reflection-free deserialization.
     */
    @Serializable
    private data class MetadataJson(
        val steps: Map<String, StepJson>,
        val globalVariables: Map<String, GlobalVariableJson>,
    ) {
        fun toBundledMetadata(): BundledJenkinsMetadata {
            val stepsMap = steps.map { (stepName, step) ->
                stepName to JenkinsStepMetadata(
                    name = stepName,
                    plugin = step.plugin,
                    parameters = step.parameters.map { (paramName, param) ->
                        paramName to StepParameter(
                            name = paramName,
                            type = param.type,
                            required = param.required,
                            default = param.default,
                            documentation = param.documentation,
                        )
                    }.toMap(),
                    documentation = step.documentation,
                )
            }.toMap()

            val globalVarsMap = globalVariables.map { (varName, globalVar) ->
                varName to GlobalVariableMetadata(
                    name = varName,
                    type = globalVar.type,
                    documentation = globalVar.documentation,
                )
            }.toMap()

            return BundledJenkinsMetadata(
                steps = stepsMap,
                globalVariables = globalVarsMap,
            )
        }
    }

    @Serializable
    private data class StepJson(
        val plugin: String,
        val parameters: Map<String, ParameterJson>,
        val documentation: String? = null,
    )

    @Serializable
    private data class ParameterJson(
        val type: String,
        val required: Boolean = false,
        val default: String? = null,
        val documentation: String? = null,
    )

    @Serializable
    private data class GlobalVariableJson(val type: String, val documentation: String? = null)
}
