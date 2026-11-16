// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("TooGenericExceptionCaught")

package com.github.albertocavalcante.gvy.jenkins.config

import com.github.albertocavalcante.gvy.gdsl.GdslExecutor
import com.github.albertocavalcante.gvy.gdsl.GdslLoader
import com.github.albertocavalcante.gvy.jenkins.gdsl.JenkinsGdslAdapter
import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataProvenance
import com.github.albertocavalcante.gvy.jenkins.metadata.SourceKind
import com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }
    private val gdslLoader = GdslLoader()
    private val gdslExecutor = GdslExecutor()
    private val gdslAdapter = JenkinsGdslAdapter()

    companion object {
        private const val CONFIG_DIR = ".gls"
        private const val CONFIG_FILE = "jenkins.json"
        private const val USER_GDSL_PLUGIN = "user-gdsl"
    }

    /**
     * Load user configuration from workspace.
     *
     * @return Configuration or null if not present/invalid
     */
    fun loadConfig(): JenkinsUserConfig? {
        val configPath = workspaceRoot.resolve(CONFIG_DIR).resolve(CONFIG_FILE)

        if (!Files.exists(configPath)) {
            logger.debug { "No user config found at $configPath" }
            return null
        }

        return try {
            val content = Files.readString(configPath)
            val root = json.parseToJsonElement(content).jsonObject
            json.decodeFromString<JenkinsUserConfigJson>(content).toConfig(root)
        } catch (e: Exception) {
            logger.warn { "Failed to parse user config at $configPath: ${e.message}" }
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

        // Preserve the list-valued catalogue even if every GDSL method is overloaded.
        val gdsl = config.gdslFile?.let(::loadGdslMetadata)
        val steps = gdsl?.steps.orEmpty().toMutableMap()

        // Apply metadata overrides (higher priority than GDSL)
        steps.putAll(config.metadataOverrides)

        if (steps.isEmpty() && gdsl?.globalVariables.isNullOrEmpty() && gdsl?.typedCatalogue?.symbols.isNullOrEmpty()) {
            return null
        }

        return BundledJenkinsMetadata(
            steps = steps,
            globalVariables = gdsl?.globalVariables.orEmpty(),
            typedCatalogue = gdsl?.typedCatalogue,
            legacyOverrides = config.metadataOverrides,
            legacyBaseSteps = gdsl?.steps.orEmpty(),
            postConditions = emptyMap(),
            declarativeOptions = emptyMap(),
            agentTypes = emptyMap(),
        )
    }

    /**
     * Load steps from a GDSL file, read from its AST (never run) like every other GDSL source.
     */
    private fun loadGdslMetadata(gdslPath: String): BundledJenkinsMetadata? {
        val fullPath = workspaceRoot.resolve(gdslPath)
        // The loader refuses missing, non-regular and oversized files before reading them.
        val loaded = gdslLoader.loadGdslFile(fullPath.toString())
        val content = loaded.content ?: run {
            logger.warn { "Could not load GDSL file $fullPath: ${loaded.error}" }
            return null
        }

        return try {
            val parsed = gdslExecutor.executeAndCapture(content, fullPath.toString())
            if (!parsed.success) {
                logger.warn { "Failed to read GDSL file $fullPath: ${parsed.error}" }
                return null
            }
            gdslAdapter.convert(
                parsed,
                pluginSource = USER_GDSL_PLUGIN,
                sourceOrigin = fullPath.toString(),
                sourceNamespace = fullPath.toAbsolutePath().normalize().toUri().toString(),
            )
        } catch (e: Exception) {
            logger.warn { "Failed to read GDSL file $fullPath: ${e.message}" }
            null
        }
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
    fun toConfig(root: JsonObject): JenkinsUserConfig = JenkinsUserConfig(
        jenkinsVersion = jenkinsVersion,
        gdslFile = gdslFile,
        plugins = plugins,
        metadataOverrides = metadataOverrides.map { (name, override) ->
            val raw = (root["metadataOverrides"] as? JsonObject)?.get(name) as? JsonObject
            val rawParams = raw?.get("parameters") as? JsonObject
            name to JenkinsStepMetadata(
                name = name,
                plugin = override.plugin,
                provenance = MetadataProvenance(SourceKind.USER_OVERRIDE, legacyPlugin = override.plugin),
                documentation = override.documentation,
                documentationSpecified = raw?.containsKey("documentation") == true,
                parameters = override.parameters.map { (paramName, param) ->
                    val rawParam = rawParams?.get(paramName) as? JsonObject
                    paramName to StepParameter(
                        name = paramName,
                        type = param.type,
                        required = param.required,
                        default = param.default,
                        documentation = param.documentation,
                        requiredSpecified = rawParam?.containsKey("required") == true,
                        defaultSpecified = rawParam?.containsKey("default") == true,
                        documentationSpecified = rawParam?.containsKey("documentation") == true,
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
    val required: Boolean? = null,
    val default: String? = null,
    val documentation: String? = null,
)
