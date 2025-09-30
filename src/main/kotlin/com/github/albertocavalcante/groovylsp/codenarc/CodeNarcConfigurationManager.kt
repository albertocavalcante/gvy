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
        ruleSetString = defaultGroovyRuleset,
        configSource = "default-groovy",
        propertiesFile = propertiesFile,
    )

    /**
     * Creates a specialized configuration for Jenkins projects.
     */
    private fun createJenkinsConfig(propertiesFile: String? = null): CodeNarcConfig = CodeNarcConfig(
        ruleSetString = jenkinsRuleset,
        configSource = "default-jenkins",
        isJenkinsProject = true,
        propertiesFile = propertiesFile,
    )

    /**
     * Default ruleset for general Groovy projects.
     * Provides a balanced set of rules that catch common issues without being overly strict.
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
            rule('TrailingWhitespace')
            rule('ConsecutiveBlankLines') {
                length = 3  // Allow up to 2 blank lines
            }

            // Naming conventions
            rule('MethodName') {
                regex = /^[a-z][a-zA-Z0-9_]*$/
            }

            rule('VariableName') {
                regex = /^[a-z][a-zA-Z0-9_]*$/
            }

            rule('FieldName') {
                regex = /^[a-z][a-zA-Z0-9_]*$/
            }

            // Size and complexity (reasonable limits)
            rule('MethodSize') {
                maxLines = 100
            }

            rule('ClassSize') {
                maxLines = 500
            }

            // Concurrency (important for correctness)
            ruleset('rulesets/concurrency.xml')

            // Design rules (subset)
            rule('PublicInstanceField')
            rule('BuilderMethodWithSideEffects')
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
                rule('UnusedVariable') {
                    // Jenkins provides these variables implicitly
                    ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,scm'
                }
            }

            // Exception handling (very lenient for scripts)
            rule('CatchArrayIndexOutOfBoundsException')
            rule('CatchNullPointerException')

            // Formatting (minimal)
            rule('TrailingWhitespace')

            // Naming (Jenkins-aware)
            rule('MethodName') {
                // Allow Jenkins DSL method patterns and step names
                regex = /^[a-z][a-zA-Z0-9_]*$|^call$|^pipeline$|^agent$|^stages$|^stage$|^steps$|^sh$|^bat$|^script$|^node$|^build$|^checkout$|^git$|^parallel$/
            }

            rule('VariableName') {
                // More lenient for Jenkins variables
                regex = /^[a-z][a-zA-Z0-9_]*$|^[A-Z][A-Z0-9_]*$/
                // Allow common Jenkins variable patterns
                ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,BRANCH_NAME,CHANGE_ID'
            }

            // Size limits (more generous for pipeline scripts)
            rule('MethodSize') {
                maxLines = 150
            }

            rule('ClassSize') {
                maxLines = 1000
            }

            // Jenkins-specific rules
            rule('UnnecessaryGetter') {
                enabled = false  // Jenkins uses property access heavily
            }

            rule('UnnecessaryPublicModifier') {
                enabled = false  // Often explicit in Jenkins for clarity
            }

            // Allow script-like patterns
            rule('ScriptNotInClass') {
                enabled = false  // Jenkinsfiles are scripts
            }

            rule('VariableTypeRequired') {
                enabled = false  // Jenkins scripts often use def
            }
        }
    """.trimIndent()
}
