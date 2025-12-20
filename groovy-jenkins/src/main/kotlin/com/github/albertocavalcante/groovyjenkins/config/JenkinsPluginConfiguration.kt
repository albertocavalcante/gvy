@file:Suppress("TooGenericExceptionCaught") // File I/O uses catch-all for resilience

package com.github.albertocavalcante.groovyjenkins.config

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Configuration for Jenkins plugins to be used for LSP features.
 *
 * Users can specify their installed plugins in several ways:
 * 1. **plugins.txt file**: Standard Jenkins plugin list format
 * 2. **Jenkinsfile plugins block**: Parsed from declarative pipeline
 * 3. **Environment variable**: JENKINS_PLUGINS_FILE pointing to a list
 *
 * File format (plugins.txt):
 * ```
 * workflow-aggregator:2.6
 * kubernetes:1.30.0
 * docker-workflow:1.28
 * # Comments start with #
 * ```
 */
class JenkinsPluginConfiguration {

    private val logger = LoggerFactory.getLogger(JenkinsPluginConfiguration::class.java)

    /**
     * Parsed plugin entry with coordinates.
     */
    data class PluginEntry(val shortName: String, val version: String?) {
        /**
         * Attempt to derive Maven coordinates.
         *
         * Most Jenkins plugins follow the pattern:
         * - Group ID: org.jenkins-ci.plugins or org.jenkins-ci.plugins.workflow
         * - Artifact ID: same as shortName
         */
        fun toMavenCoordinates(): MavenCoordinates? {
            if (version == null) return null

            // Common group IDs for Jenkins plugins
            val groupId = when {
                shortName.startsWith("workflow-") -> "org.jenkins-ci.plugins.workflow"
                shortName == "kubernetes" -> "org.csanchez.jenkins.plugins"
                else -> "org.jenkins-ci.plugins"
            }

            return MavenCoordinates(groupId, shortName, version)
        }

        override fun toString(): String = if (version != null) "$shortName:$version" else shortName
    }

    data class MavenCoordinates(val groupId: String, val artifactId: String, val version: String)

    companion object {
        private const val DEFAULT_PLUGINS_FILENAME = "plugins.txt"
        private const val JENKINS_PLUGINS_FILE_ENV = "JENKINS_PLUGINS_FILE"

        /**
         * Search for plugins.txt in standard locations relative to workspace.
         */
        fun findPluginsFile(workspaceRoot: Path): Path? {
            // Check standard locations
            val candidates = listOf(
                workspaceRoot.resolve(DEFAULT_PLUGINS_FILENAME),
                workspaceRoot.resolve("jenkins").resolve(DEFAULT_PLUGINS_FILENAME),
                workspaceRoot.resolve(".jenkins").resolve(DEFAULT_PLUGINS_FILENAME),
                workspaceRoot.resolve("config").resolve(DEFAULT_PLUGINS_FILENAME),
            )

            // Check environment variable first
            val envPath = System.getenv(JENKINS_PLUGINS_FILE_ENV)
            if (envPath != null) {
                val path = Path.of(envPath)
                if (Files.exists(path)) return path
            }

            return candidates.firstOrNull { Files.exists(it) }
        }
    }

    /**
     * Parse a plugins.txt file.
     *
     * Format:
     * ```
     * plugin-name:version
     * plugin-name  # version optional
     * # comment
     * ```
     */
    fun parsePluginsFile(path: Path): List<PluginEntry> {
        if (!Files.exists(path)) {
            logger.warn("Plugins file not found: {}", path)
            return emptyList()
        }

        return try {
            Files.readAllLines(path)
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { line -> parsePluginLine(line) }
        } catch (e: Exception) {
            logger.error("Failed to parse plugins file: {}", path, e)
            emptyList()
        }
    }

    /**
     * Parse a single plugin line.
     *
     * Supports formats:
     * - plugin-name:version
     * - plugin-name (no version)
     * - plugin-name:version:url (extended format, URL ignored)
     */
    private fun parsePluginLine(line: String): PluginEntry? {
        val parts = line.split(":")

        return when {
            parts.isEmpty() -> null
            parts.size == 1 -> PluginEntry(parts[0].trim(), null)
            else -> PluginEntry(parts[0].trim(), parts[1].trim())
        }
    }

    /**
     * Load plugins from workspace, checking all standard locations.
     */
    fun loadPluginsFromWorkspace(workspaceRoot: Path): List<PluginEntry> {
        val pluginsFile = findPluginsFile(workspaceRoot)

        return if (pluginsFile != null) {
            logger.info("Loading plugins from: {}", pluginsFile)
            parsePluginsFile(pluginsFile)
        } else {
            logger.debug("No plugins.txt found in workspace: {}", workspaceRoot)
            emptyList()
        }
    }
}
