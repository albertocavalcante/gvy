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

    // TODO: Remove embedded string configurations below (lines 226-401)
    // They are no longer used since the system now loads from .groovy files.
    // Keeping temporarily for reference during transition period.

    /**
     * Default ruleset for general Groovy projects.
     * Provides a balanced set of rules that catch common issues without being overly strict.
     * @deprecated Use .groovy files in resources/codenarc/rulesets/ instead
     */
    private val defaultGroovyRuleset = """
        ruleset {
            description 'Groovy LSP Default Rules for General Projects'

            // Core quality rules
            ruleset('rulesets/basic.xml') {
                // Exclude overly strict instantiation rules
                exclude 'ExplicitHashSetInstantiation'
                exclude 'ExplicitArrayListInstantiation'
                exclude 'ExplicitLinkedListInstantiation'
                exclude 'ExplicitCallToAndMethod'
                exclude 'ExplicitCallToOrMethod'
            }

            // Import organization
            ruleset('rulesets/imports.xml') {
                exclude 'MissingBlankLineAfterImports'
                exclude 'NoWildcardImports'  // Common in Groovy
            }

            // Unused code detection
            ruleset('rulesets/unused.xml')

            // Exception handling
            ruleset('rulesets/exceptions.xml') {
                exclude 'CatchException'  // Common in scripts
                exclude 'CatchThrowable'
                exclude 'ThrowException'  // Sometimes necessary
            }

            // Formatting (basic subset)
            ruleset('rulesets/formatting.xml') {
                include 'TrailingWhitespace'
                exclude 'ConsecutiveBlankLines'  // Configure separately
            }
            // Configured formatting rules
            ConsecutiveBlankLines {
                length = 3  // Allow up to 2 blank lines
            }

            // Naming conventions (exclude from bulk import, configure separately)
            ruleset('rulesets/naming.xml') {
                exclude 'MethodName'
                exclude 'VariableName'
                exclude 'FieldName'
            }

            // Configured naming rules
            MethodName {
                regex = /^[a-z][a-zA-Z0-9_]*$/
            }

            VariableName {
                regex = /^[a-z][a-zA-Z0-9_]*$/
            }

            FieldName {
                regex = /^[a-z][a-zA-Z0-9_]*$/
            }

            // Size and complexity (exclude from bulk import, configure separately)
            ruleset('rulesets/size.xml') {
                exclude 'MethodSize'
                exclude 'ClassSize'
            }

            // Configured size rules
            MethodSize {
                maxLines = 100
            }

            ClassSize {
                maxLines = 500
            }

            // Concurrency (important for correctness)
            ruleset('rulesets/concurrency.xml')

            // Design rules (subset)
            ruleset('rulesets/design.xml') {
                include 'PublicInstanceField'
                include 'BuilderMethodWithSideEffects'
            }
        }
    """.trimIndent()

    /**
     * Specialized ruleset for Jenkins projects.
     * More lenient with DSL patterns and script-like code.
     */
    private val jenkinsRuleset = """
        ruleset {
            description 'Groovy LSP Rules for Jenkins Projects'

            // Basic quality rules (relaxed)
            ruleset('rulesets/basic.xml') {
                // Exclude rules that conflict with Jenkins DSL patterns
                exclude 'ExplicitHashSetInstantiation'
                exclude 'ExplicitArrayListInstantiation'
                exclude 'ExplicitLinkedListInstantiation'
                exclude 'ExplicitCallToAndMethod'
                exclude 'ExplicitCallToOrMethod'
                exclude 'HardCodedWindowsFileSeparator'  // May be intentional
                exclude 'HardCodedWindowsRootDirectory'
            }

            // Import rules (very relaxed for Jenkins)
            ruleset('rulesets/imports.xml') {
                exclude 'MissingBlankLineAfterImports'
                exclude 'NoWildcardImports'
                exclude 'UnnecessaryGroovyImport'  // Sometimes needed for clarity
            }

            // Unused code (but allow Jenkins implicit variables)
            ruleset('rulesets/unused.xml') {
                exclude 'UnusedVariable'  // Configure separately
            }

            // Configure UnusedVariable rule separately with Jenkins-specific exclusions
            UnusedVariable {
                // Jenkins provides these variables implicitly
                ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,scm'
            }

            // Exception handling (very lenient for scripts)
            ruleset('rulesets/exceptions.xml') {
                include 'CatchArrayIndexOutOfBoundsException'
                include 'CatchNullPointerException'
            }

            // Formatting (minimal)
            ruleset('rulesets/formatting.xml') {
                include 'TrailingWhitespace'
            }

            // Naming (Jenkins-aware) - configure separately
            MethodName {
                // Allow Jenkins DSL method patterns and step names
                regex = /^[a-z][a-zA-Z0-9_]*$|^call$|^pipeline$|^agent$|^stages$|^stage$|^steps$|^sh$|^bat$|^script$|^node$|^build$|^checkout$|^git$|^parallel$/
            }

            VariableName {
                // More lenient for Jenkins variables
                regex = /^[a-z][a-zA-Z0-9_]*$|^[A-Z][A-Z0-9_]*$/
                // Allow common Jenkins variable patterns
                ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,BRANCH_NAME,CHANGE_ID'
            }

            // Size limits (more generous for pipeline scripts)
            MethodSize {
                maxLines = 150
            }

            ClassSize {
                maxLines = 1000
            }

            // Jenkins-specific exclusions (using ruleset exclusions instead of non-existent rules)
            // Note: Some rules don't exist in current CodeNarc version, so we exclude them from rulesets instead

            // Exclude rules that would conflict with Jenkins DSL patterns
            ruleset('rulesets/groovyism.xml') {
                exclude 'ExplicitCallToGetAtMethod'
                exclude 'ExplicitCallToPutAtMethod'
            }

            // Basic formatting and conventions (very minimal for scripts)
            ruleset('rulesets/convention.xml') {
                exclude 'CompileStatic'  // Not required for Jenkins scripts
                exclude 'NoDef'          // Jenkins scripts commonly use 'def'
            }
        }
    """.trimIndent()

    /**
     * Loads a ruleset from a resource file.
     *
     * @param resourcePath Path to the resource file (e.g., "codenarc/rulesets/base/default.groovy")
     * @return The content of the ruleset file
     * @throws IllegalStateException if the resource cannot be found or read
     */
    private fun loadRulesetFromResource(resourcePath: String): String {
        try {
            val resourceStream = javaClass.classLoader.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Ruleset resource not found: $resourcePath")

            return resourceStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logger.error("Failed to load ruleset from resource: $resourcePath", e)
            throw IllegalStateException("Could not load CodeNarc ruleset from $resourcePath", e)
        }
    }
}
