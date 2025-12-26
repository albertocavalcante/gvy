@file:Suppress("TooGenericExceptionCaught")

package com.github.albertocavalcante.groovyjenkins.config

import com.github.albertocavalcante.groovyjenkins.gdsl.GdslParser
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads user-provided Jenkins metadata configuration.
 *
 * Supports:
 * - Custom GDSL files for internal/custom plugins
 * - Metadata overrides for step definitions
 * - Jenkins version configuration
 * - Plugin version specifications
 *
 * Configuration is read from `.gls/jenkins.json` in the workspace root.
 */
class UserMetadataLoader(private val workspaceRoot: Path) {
    private val logger = LoggerFactory.getLogger(UserMetadataLoader::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val CONFIG_DIR = ".gls"
        private const val CONFIG_FILE = "jenkins.json"
    }

    /**
     * Load user configuration from workspace.
     *
     * @return Configuration or null if not present/invalid
     */
    fun loadConfig(): JenkinsUserConfig? {
        val configPath = workspaceRoot.resolve(CONFIG_DIR).resolve(CONFIG_FILE)

        if (!Files.exists(configPath)) {
            logger.debug("No user config found at {}", configPath)
            return null
        }

        return try {
            val content = Files.readString(configPath)
            json.decodeFromString<JenkinsUserConfigJson>(content).toConfig()
        } catch (e: Exception) {
            logger.warn("Failed to parse user config at {}: {}", configPath, e.message)
            null
        }
    }

    /**
     * Load user-provided metadata (GDSL + overrides).
     *
     * @return Metadata from user config, or null if not configured
     */
    fun load(): BundledJenkinsMetadata? {
        val config = loadConfig() ?: return null

        val steps = mutableMapOf<String, JenkinsStepMetadata>()

        // Load GDSL file if configured
        config.gdslFile?.let { gdslPath ->
            loadGdslSteps(gdslPath)?.let { gdslSteps ->
                steps.putAll(gdslSteps)
            }
        }

        // Apply metadata overrides (higher priority than GDSL)
        steps.putAll(config.metadataOverrides)

        if (steps.isEmpty()) {
            return null
        }

        return BundledJenkinsMetadata(
            steps = steps,
            globalVariables = emptyMap(),
            postConditions = emptyMap(),
            declarativeOptions = emptyMap(),
            agentTypes = emptyMap(),
        )
    }

    /**
     * Load steps from a GDSL file.
     */
    private fun loadGdslSteps(gdslPath: String): Map<String, JenkinsStepMetadata>? {
        val fullPath = workspaceRoot.resolve(gdslPath)

        if (!Files.exists(fullPath)) {
            logger.warn("GDSL file not found: {}", fullPath)
            return null
        }

        return try {
            val content = Files.readString(fullPath)
            val parsed = GdslParser.parseMerged(content)

            parsed.steps.mapValues { (name, step) ->
                JenkinsStepMetadata(
                    name = name,
                    plugin = "user-gdsl",
                    documentation = step.documentation,
                    parameters = step.namedParameters.ifEmpty { step.parameters }.mapValues { (paramName, paramType) ->
                        StepParameter(
                            name = paramName,
                            type = normalizeType(paramType),
                            required = false, // GDSL doesn't specify required
                        )
                    },
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse GDSL file {}: {}", fullPath, e.message)
            null
        }
    }

    /**
     * Normalize Java type names to simple names.
     */
    private fun normalizeType(type: String): String = when {
        type.startsWith("java.lang.") -> type.removePrefix("java.lang.")
        type.startsWith("java.util.") -> type.removePrefix("java.util.")
        else -> type
    }
}

/**
 * User configuration for Jenkins IntelliSense.
 */
data class JenkinsUserConfig(
    /**
     * Jenkins version for version-specific metadata
     */
    val jenkinsVersion: String? = null,

    /**
     * Path to user-provided GDSL file (relative to workspace)
     */
    val gdslFile: String? = null,

    /**
     * Plugin versions to use for metadata
     */
    val plugins: Map<String, String> = emptyMap(),

    /**
     * Direct metadata overrides for steps
     */
    val metadataOverrides: Map<String, JenkinsStepMetadata> = emptyMap(),
)

/**
 * JSON structure for user configuration file.
 */
@Serializable
private data class JenkinsUserConfigJson(
    val jenkinsVersion: String? = null,
    val gdslFile: String? = null,
    val plugins: Map<String, String> = emptyMap(),
    val metadataOverrides: Map<String, StepOverrideJson> = emptyMap(),
) {
    fun toConfig(): JenkinsUserConfig = JenkinsUserConfig(
        jenkinsVersion = jenkinsVersion,
        gdslFile = gdslFile,
        plugins = plugins,
        metadataOverrides = metadataOverrides.map { (name, override) ->
            name to JenkinsStepMetadata(
                name = name,
                plugin = override.plugin,
                documentation = override.documentation,
                parameters = override.parameters.map { (paramName, param) ->
                    paramName to StepParameter(
                        name = paramName,
                        type = param.type,
                        required = param.required,
                        default = param.default,
                        documentation = param.documentation,
                    )
                }.toMap(),
            )
        }.toMap(),
    )
}

@Serializable
private data class StepOverrideJson(
    val plugin: String,
    val documentation: String? = null,
    val parameters: Map<String, ParameterOverrideJson> = emptyMap(),
)

@Serializable
private data class ParameterOverrideJson(
    val type: String,
    val required: Boolean = false,
    val default: String? = null,
    val documentation: String? = null,
)
