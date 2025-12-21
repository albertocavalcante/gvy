package com.github.albertocavalcante.diagnostics.codenarc

import com.github.albertocavalcante.diagnostics.api.WorkspaceContext
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
    fun resolve(context: WorkspaceContext): RulesetConfiguration
}

/**
 * Loads resources from a given path.
 * Extracted for testability - allows mocking resource loading in tests.
 */
fun interface ResourceLoader {
    /**
     * Loads content from the specified resource path.
     * @param resourcePath The path to the resource
     * @return The content as a string, or null if not found
     */
    fun load(resourcePath: String): String?
}

/**
 * Default implementation that loads resources from the classpath.
 */
class ClasspathResourceLoader : ResourceLoader {
    override fun load(resourcePath: String): String? =
        this::class.java.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
            stream.reader().readText()
        }
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
    private val resourceLoader: ResourceLoader = ClasspathResourceLoader(),
) : RulesetResolver {

    /**
     * Clears any cached ruleset data and forces re-resolution on next call.
     * Useful when configuration files change.
     */
    fun reloadRulesets() {
        logger.info("Reload requested for CodeNarc rulesets")
        // This method is a placeholder for future cache invalidation logic.
        // Currently, rulesets are resolved fresh on each call to resolve(), so no cache clearing is needed.
    }

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

    override fun resolve(context: WorkspaceContext): RulesetConfiguration {
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
    private fun resolveRulesetContent(context: WorkspaceContext): ResolvedRuleset {
        // 1. Try explicit workspace configuration files
        if (context.root != null) {
            val workspaceRuleset = loadFromWorkspaceFiles(context.root!!)
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
    private fun resolvePropertiesFile(context: WorkspaceContext): String? {
        val config = context.getConfiguration()

        // Check if auto-detection is disabled
        if (!config.autoDetectConfig) {
            return null
        }

        // Check for explicit override in server configuration
        config.propertiesFile?.let { explicitPath ->
            return resolveExplicitPropertiesPath(explicitPath, context.root)
        }

        // Fall back to auto-detection in workspace
        return if (context.root != null) {
            findPropertiesFile(context.root!!)
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
     *
     * Fallback chain:
     * 1. Try custom DSL ruleset for project type
     * 2. Try default DSL ruleset
     * 3. Fall back to CodeNarc's bundled XML rulesets (wrapped in minimal DSL)
     */
    private fun loadProjectTypeDefault(context: WorkspaceContext): ResolvedRuleset {
        val projectType = if (context.root != null) {
            projectTypeDetector.detect(context.root!!)
        } else {
            ProjectType.PlainGroovy
        }

        // Try custom DSL rulesets first
        val customResourcePath = when (projectType) {
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

        // Try custom ruleset
        loadRulesetFromResource(customResourcePath)?.let {
            return ResolvedRuleset(it, "resource:$customResourcePath")
        }

        // Try default ruleset as fallback
        val defaultResourcePath = "codenarc/rulesets/base/default.groovy"
        loadRulesetFromResource(defaultResourcePath)?.let {
            logger.info("Falling back to default ruleset: {}", defaultResourcePath)
            return ResolvedRuleset(it, "resource:$defaultResourcePath")
        }

        // Final fallback: Use CodeNarc's bundled XML rulesets
        // Generate minimal DSL wrapper that references bundled rulesets
        val bundledRulesetPath = when (projectType) {
            is ProjectType.JenkinsLibrary -> "rulesets/jenkins.xml"
            else -> "rulesets/basic.xml"
        }

        logger.warn(
            "Custom rulesets not found in classpath. Falling back to CodeNarc bundled ruleset: {}",
            bundledRulesetPath,
        )

        // Generate minimal DSL wrapper for bundled XML ruleset
        val bundledWrapper = """
            ruleset {
                description 'Fallback to CodeNarc bundled ruleset'
                ruleset('$bundledRulesetPath')
            }
        """.trimIndent()

        return ResolvedRuleset(bundledWrapper, "bundled:$bundledRulesetPath")
    }

    /**
     * Loads ruleset content from a classpath resource using the injected ResourceLoader.
     */
    private fun loadRulesetFromResource(resourcePath: String): String? = try {
        resourceLoader.load(resourcePath)?.also {
            logger.debug("Successfully loaded ruleset from {} ({} characters)", resourcePath, it.length)
        }
    } catch (e: Exception) {
        logger.debug("Failed to load ruleset from resource: {}", resourcePath, e)
        null
    }

    /**
     * Internal data class for resolved ruleset content.
     */
    private data class ResolvedRuleset(val content: String, val source: String)
}
