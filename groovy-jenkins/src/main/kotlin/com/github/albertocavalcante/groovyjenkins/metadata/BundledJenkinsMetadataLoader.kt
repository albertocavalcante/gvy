@file:Suppress(
    "TooGenericExceptionCaught", // JSON parsing uses catch-all for resilience
    "LongMethod", // toBundledMetadata transforms all metadata types
    "UseCheckOrError", // Explicit IllegalStateException provides clearer error messages
)

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

    /**
     * JSON structure for metadata file.
     *
     * Uses kotlinx.serialization for type-safe, reflection-free deserialization.
     */
    @Serializable
    private data class MetadataJson(
        val jenkinsVersion: String? = null,
        val steps: Map<String, StepJson>,
        val globalVariables: Map<String, GlobalVariableJson>,
        val postConditions: Map<String, PostConditionJson> = emptyMap(),
        val declarativeOptions: Map<String, DeclarativeOptionJson> = emptyMap(),
        val agentTypes: Map<String, AgentTypeJson> = emptyMap(),
    ) {
        fun toBundledMetadata(): BundledJenkinsMetadata {
            val stepsMap = steps.map { (stepName, step) ->
                stepName to JenkinsStepMetadata(
                    name = stepName,
                    plugin = step.plugin,
                    positionalParams = step.positionalParams,
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

            val postConditionsMap = postConditions.map { (condName, cond) ->
                condName to PostConditionMetadata(
                    name = condName,
                    description = cond.description,
                    executionOrder = cond.executionOrder,
                )
            }.toMap()

            val declarativeOptionsMap = declarativeOptions.map { (optName, opt) ->
                optName to DeclarativeOptionMetadata(
                    name = optName,
                    plugin = opt.plugin,
                    parameters = opt.parameters.map { (paramName, param) ->
                        paramName to StepParameter(
                            name = paramName,
                            type = param.type,
                            required = param.required,
                            default = param.default,
                            documentation = param.documentation,
                        )
                    }.toMap(),
                    documentation = opt.documentation,
                )
            }.toMap()

            val agentTypesMap = agentTypes.map { (agentName, agent) ->
                agentName to AgentTypeMetadata(
                    name = agentName,
                    parameters = agent.parameters.map { (paramName, param) ->
                        paramName to StepParameter(
                            name = paramName,
                            type = param.type,
                            required = param.required,
                            default = param.default,
                            documentation = param.documentation,
                        )
                    }.toMap(),
                    documentation = agent.documentation,
                )
            }.toMap()

            return BundledJenkinsMetadata(
                steps = stepsMap,
                globalVariables = globalVarsMap,
                postConditions = postConditionsMap,
                declarativeOptions = declarativeOptionsMap,
                agentTypes = agentTypesMap,
                jenkinsVersion = jenkinsVersion,
            )
        }
    }

    @Serializable
    private data class StepJson(
        val plugin: String,
        val positionalParams: List<String> = emptyList(),
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

    @Serializable
    private data class PostConditionJson(val description: String, val executionOrder: Int = 0)

    @Serializable
    private data class DeclarativeOptionJson(
        val plugin: String,
        val parameters: Map<String, ParameterJson> = emptyMap(),
        val documentation: String? = null,
    )

    @Serializable
    private data class AgentTypeJson(
        val parameters: Map<String, ParameterJson> = emptyMap(),
        val documentation: String? = null,
    )
}
