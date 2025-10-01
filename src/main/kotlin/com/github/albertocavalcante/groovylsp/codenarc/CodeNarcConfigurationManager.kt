package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Manages CodeNarc configuration and ruleset loading.
 *
 * This manager provides a centralized way to load and manage CodeNarc
 * configurations from various sources including:
 * - Project-specific .codenarc files
 * - codenarc.properties files
 * - Default rulesets for different project types
 */
@Suppress("TooGenericExceptionCaught") // CodeNarc interop layer handles all config loading errors
class CodeNarcConfigurationManager {

    companion object {
        private val logger = LoggerFactory.getLogger(CodeNarcConfigurationManager::class.java)

        // Configuration file names in order of precedence
        private val CONFIG_FILENAMES = listOf(
            ".codenarc",
            "config/codenarc/rules.groovy",
            "codenarc.groovy",
        )

        // Properties file names in order of precedence
        private val PROPERTIES_FILENAMES = listOf(
            "codenarc.properties",
            ".codenarc/codenarc.properties",
            "config/codenarc/codenarc.properties",
        )
    }

    /**
     * Data class representing a CodeNarc configuration.
     */
    data class CodeNarcConfig(
        val ruleSetString: String,
        val configSource: String = "default",
        val isJenkinsProject: Boolean = false,
        val propertiesFile: String? = null,
    )

    /**
     * Loads the appropriate CodeNarc configuration for the given workspace.
     *
     * @param workspaceRoot The root URI of the workspace
     * @return CodeNarc configuration with appropriate ruleset
     */
    fun loadConfiguration(workspaceRoot: URI?): CodeNarcConfig = loadConfiguration(workspaceRoot, null)

    /**
     * Loads the appropriate CodeNarc configuration for the given workspace with server configuration.
     *
     * @param workspaceRoot The root URI of the workspace
     * @param serverConfig Server configuration that may override properties file detection
     * @return CodeNarc configuration with appropriate ruleset
     */
    fun loadConfiguration(workspaceRoot: URI?, serverConfig: ServerConfiguration?): CodeNarcConfig {
        if (workspaceRoot == null) {
            logger.debug("No workspace root provided, using default configuration")
            return createDefaultConfig()
        }

        try {
            val workspacePath = Paths.get(workspaceRoot)

            // Check if this is a Jenkins project
            val isJenkinsProject = detectJenkinsProject(workspacePath)

            // Try to load from configuration files
            val configFromFile = loadConfigurationFromFiles(workspacePath, serverConfig)
            if (configFromFile != null) {
                return configFromFile.copy(isJenkinsProject = isJenkinsProject)
            }

            // Fall back to appropriate default based on project type
            // Check for properties file even without custom ruleset
            val propertiesFile = resolvePropertiesFile(workspacePath, serverConfig)
            return if (isJenkinsProject) {
                createJenkinsConfig(propertiesFile)
            } else {
                createDefaultConfig(propertiesFile)
            }
        } catch (e: Exception) {
            logger.warn("Failed to load CodeNarc configuration for workspace: $workspaceRoot", e)
            return createDefaultConfig()
        }
    }

    /**
     * Attempts to load configuration from various configuration files.
     */
    private fun loadConfigurationFromFiles(
        workspacePath: Path,
        serverConfig: ServerConfiguration? = null,
    ): CodeNarcConfig? {
        val propertiesFile = resolvePropertiesFile(workspacePath, serverConfig)

        for (configFileName in CONFIG_FILENAMES) {
            val configFile = workspacePath.resolve(configFileName)
            val config = tryLoadConfigFromFile(configFile, configFileName, propertiesFile)
            if (config != null) return config
        }
        return null
    }

    private fun tryLoadConfigFromFile(
        configFile: Path,
        configFileName: String,
        propertiesFile: String?,
    ): CodeNarcConfig? {
        if (!configFile.exists()) return null

        try {
            val content = configFile.readText()
            if (content.isNotEmpty()) {
                logger.info("Loaded CodeNarc configuration from: $configFileName")
                return CodeNarcConfig(
                    ruleSetString = content,
                    configSource = configFileName,
                    propertiesFile = propertiesFile,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to read CodeNarc configuration from: $configFileName", e)
        }
        return null
    }

    /**
     * Resolves the properties file to use, considering server configuration overrides.
     */
    private fun resolvePropertiesFile(workspacePath: Path, serverConfig: ServerConfiguration?): String? {
        // Check if auto-detection is disabled
        if (serverConfig?.codeNarcAutoDetect == false) {
            return null
        }

        // Check for explicit override in server configuration
        serverConfig?.codeNarcPropertiesFile?.let { explicitPath ->
            if (explicitPath.startsWith("file:")) {
                // Absolute path with file: prefix
                val path = Paths.get(URI.create(explicitPath))
                if (path.exists()) {
                    logger.info("Using explicit CodeNarc properties file: $explicitPath")
                    return path.toString()
                } else {
                    logger.warn("Explicit CodeNarc properties file not found: $explicitPath")
                }
            } else {
                // Relative path from workspace root
                val path = workspacePath.resolve(explicitPath)
                if (path.exists()) {
                    logger.info("Using explicit CodeNarc properties file: $explicitPath")
                    return path.toString()
                } else {
                    logger.warn("Explicit CodeNarc properties file not found: $explicitPath")
                }
            }
        }

        // Fall back to auto-detection
        return findPropertiesFile(workspacePath)
    }

    /**
     * Finds a codenarc.properties file in the workspace.
     */
    private fun findPropertiesFile(workspacePath: Path): String? {
        for (propertiesFileName in PROPERTIES_FILENAMES) {
            val propertiesFile = workspacePath.resolve(propertiesFileName)
            if (propertiesFile.exists()) {
                logger.debug("Found CodeNarc properties file: $propertiesFileName")
                return propertiesFile.toString()
            }
        }
        return null
    }

    /**
     * Detects if the workspace appears to be a Jenkins-related project.
     */
    private fun detectJenkinsProject(workspacePath: Path): Boolean {
        // Check for common Jenkins indicators
        val jenkinsIndicators = listOf(
            "Jenkinsfile",
            "vars", // Jenkins shared library
            "resources", // Jenkins shared library resources
            "pipeline", // Common pipeline directory
            ".jenkins", // Jenkins configuration directory
        )

        return jenkinsIndicators.any { indicator ->
            workspacePath.resolve(indicator).exists()
        }
    }

    /**
     * Creates the default configuration for general Groovy projects.
     */
    private fun createDefaultConfig(propertiesFile: String? = null): CodeNarcConfig = CodeNarcConfig(
        ruleSetString = loadRulesetFromResource("codenarc/rulesets/base/default.groovy"),
        configSource = "default-groovy",
        propertiesFile = propertiesFile,
    )

    /**
     * Creates a specialized configuration for Jenkins projects.
     */
    private fun createJenkinsConfig(propertiesFile: String? = null): CodeNarcConfig = CodeNarcConfig(
        ruleSetString = loadRulesetFromResource("codenarc/rulesets/frameworks/jenkins.groovy"),
        configSource = "default-jenkins",
        isJenkinsProject = true,
        propertiesFile = propertiesFile,
    )

    /**
     * Loads a ruleset from a resource file.
     *
     * @param resourcePath Path to the resource file (e.g., "codenarc/rulesets/base/default.groovy")
     * @return The content of the ruleset file
     * @throws IllegalStateException if the resource cannot be found or read
     */
    private fun loadRulesetFromResource(resourcePath: String): String {
        try {
            val resourceStream = checkNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
                "Ruleset resource not found: $resourcePath"
            }

            return resourceStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logger.error("Failed to load ruleset from resource: $resourcePath", e)
            error("Could not load CodeNarc ruleset from $resourcePath: ${e.message}")
        }
    }
}
