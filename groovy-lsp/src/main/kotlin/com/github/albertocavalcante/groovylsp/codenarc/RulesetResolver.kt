package com.github.albertocavalcante.groovylsp.codenarc

import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Configuration for a resolved ruleset.
 */
data class RulesetConfiguration(
    val rulesetContent: String,
    val propertiesFile: String? = null,
    val source: String = "unknown",
)

/**
 * Interface for resolving rulesets based on workspace configuration.
 */
interface RulesetResolver {
    /**
     * Resolves the appropriate ruleset configuration for the given context.
     *
     * @param context The workspace configuration context
     * @return The resolved ruleset configuration
     */
    fun resolve(context: WorkspaceConfiguration): RulesetConfiguration
}

/**
 * Default implementation that resolves rulesets hierarchically:
 * 1. Explicit workspace config file
 * 2. Server configuration override
 * 3. Project-type defaults
 * 4. Built-in defaults
 */
@Suppress("TooGenericExceptionCaught") // Ruleset resolution needs robust error handling
class HierarchicalRulesetResolver(
    private val projectTypeDetector: ProjectTypeDetector = DefaultProjectTypeDetector(),
) : RulesetResolver {

    companion object {
        private val logger = LoggerFactory.getLogger(HierarchicalRulesetResolver::class.java)

        // Workspace configuration files in order of precedence
        private val WORKSPACE_CONFIG_FILES = listOf(
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

    override fun resolve(context: WorkspaceConfiguration): RulesetConfiguration {
        logger.debug("Resolving ruleset configuration for context: $context")

        // Find properties file first
        val propertiesFile = resolvePropertiesFile(context)

        // Try to resolve ruleset content
        val rulesetContent = resolveRulesetContent(context)

        return RulesetConfiguration(
            rulesetContent = rulesetContent.content,
            propertiesFile = propertiesFile,
            source = rulesetContent.source,
        ).also {
            logger.info("Resolved ruleset: source=${it.source}, properties=${it.propertiesFile}")
        }
    }

    /**
     * Resolves the ruleset content from various sources.
     */
    private fun resolveRulesetContent(context: WorkspaceConfiguration): ResolvedRuleset {
        // 1. Try explicit workspace configuration files
        if (context.hasWorkspace()) {
            val workspaceRuleset = loadFromWorkspaceFiles(context.requireWorkspace())
            if (workspaceRuleset != null) {
                return workspaceRuleset
            }
        }

        // 2. Try server configuration override (future enhancement)
        // val serverOverride = loadFromServerConfig(context.serverConfig)

        // 3. Use project-type defaults
        return loadProjectTypeDefault(context)
    }

    /**
     * Resolves the properties file to use.
     */
    private fun resolvePropertiesFile(context: WorkspaceConfiguration): String? {
        val serverConfig = context.serverConfig

        // Check if auto-detection is disabled
        if (!serverConfig.codeNarcAutoDetect) {
            return null
        }

        // Check for explicit override in server configuration
        serverConfig.codeNarcPropertiesFile?.let { explicitPath ->
            return resolveExplicitPropertiesPath(explicitPath, context.workspaceRoot)
        }

        // Fall back to auto-detection in workspace
        return if (context.hasWorkspace()) {
            findPropertiesFile(context.requireWorkspace())
        } else {
            null
        }
    }

    /**
     * Resolves an explicit properties file path from server configuration.
     */
    private fun resolveExplicitPropertiesPath(explicitPath: String, workspaceRoot: Path?): String? =
        if (explicitPath.startsWith("file:")) {
            // Absolute path with file: prefix
            try {
                val path = Paths.get(URI.create(explicitPath))
                if (path.exists()) {
                    logger.info("Using explicit CodeNarc properties file: $explicitPath")
                    path.toString()
                } else {
                    logger.warn("Explicit CodeNarc properties file not found: $explicitPath")
                    null
                }
            } catch (e: Exception) {
                logger.warn("Invalid explicit CodeNarc properties file URI: $explicitPath", e)
                null
            }
        } else if (workspaceRoot != null) {
            // Relative path from workspace root
            val path = workspaceRoot.resolve(explicitPath)
            if (path.exists()) {
                logger.info("Using explicit CodeNarc properties file: $explicitPath")
                path.toString()
            } else {
                logger.warn("Explicit CodeNarc properties file not found: $explicitPath")
                null
            }
        } else {
            logger.warn("Cannot resolve relative properties path without workspace: $explicitPath")
            null
        }

    /**
     * Finds a codenarc.properties file in the workspace.
     */
    private fun findPropertiesFile(workspaceRoot: Path): String? {
        for (propertiesFileName in PROPERTIES_FILENAMES) {
            val propertiesFile = workspaceRoot.resolve(propertiesFileName)
            if (propertiesFile.exists()) {
                logger.debug("Found CodeNarc properties file: $propertiesFileName")
                return propertiesFile.toString()
            }
        }
        return null
    }

    /**
     * Attempts to load ruleset from workspace configuration files.
     */
    private fun loadFromWorkspaceFiles(workspaceRoot: Path): ResolvedRuleset? {
        for (configFileName in WORKSPACE_CONFIG_FILES) {
            val configFile = workspaceRoot.resolve(configFileName)
            val ruleset = tryLoadRulesetFromFile(configFile, configFileName)
            if (ruleset != null) return ruleset
        }
        return null
    }

    private fun tryLoadRulesetFromFile(configFile: Path, configFileName: String): ResolvedRuleset? {
        if (!configFile.exists()) return null

        return try {
            val content = configFile.readText()
            if (content.isNotEmpty()) {
                logger.info("Loaded CodeNarc ruleset from workspace file: $configFileName")
                ResolvedRuleset(content, "workspace:$configFileName")
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to read CodeNarc configuration from: $configFileName", e)
            null
        }
    }

    /**
     * Loads the appropriate default ruleset based on project type.
     */
    private fun loadProjectTypeDefault(context: WorkspaceConfiguration): ResolvedRuleset {
        val projectType = if (context.hasWorkspace()) {
            projectTypeDetector.detect(context.requireWorkspace())
        } else {
            context.projectType
        }

        val resourcePath = when (projectType) {
            is ProjectType.JenkinsLibrary -> "codenarc/rulesets/frameworks/jenkins.groovy"
            is ProjectType.GrailsApplication -> "codenarc/rulesets/frameworks/grails.groovy" // Future
            is ProjectType.SpringBootProject -> "codenarc/rulesets/frameworks/spring-boot.groovy" // Future
            is ProjectType.GradleProject -> {
                if (projectType.hasSpock) {
                    "codenarc/rulesets/frameworks/gradle-spock.groovy" // Future
                } else {
                    "codenarc/rulesets/base/default.groovy"
                }
            }
            else -> "codenarc/rulesets/base/default.groovy"
        }

        val content = loadRulesetFromResource(resourcePath)
            ?: loadRulesetFromResource("codenarc/rulesets/base/default.groovy")
            ?: error("Failed to load any ruleset from resources - check that ruleset files exist in classpath")

        return ResolvedRuleset(content, "resource:$resourcePath")
    }

    /**
     * Loads ruleset content from a classpath resource.
     */
    private fun loadRulesetFromResource(resourcePath: String): String? {
        return try {
            val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: return null

            inputStream.use { stream ->
                stream.reader().readText()
            }.also {
                logger.debug("Successfully loaded ruleset from $resourcePath (${it.length} characters)")
            }
        } catch (e: Exception) {
            logger.debug("Failed to load ruleset from resource: $resourcePath", e)
            null
        }
    }

    /**
     * Internal data class for resolved ruleset content.
     */
    private data class ResolvedRuleset(val content: String, val source: String)
}
