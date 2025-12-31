@file:Suppress("TooGenericExceptionCaught") // Configuration parsing uses catch-all for resilience

package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovyjenkins.plugins.PluginConfiguration
import org.slf4j.LoggerFactory

/**
 * Configuration for Jenkins pipeline support.
 */
data class JenkinsConfiguration(
    val filePatterns: List<String> = listOf("Jenkinsfile", "vars/*.groovy"),
    val sharedLibraries: List<SharedLibrary> = emptyList(),
    val gdslPaths: List<String> = emptyList(),
    val gdslExecutionEnabled: Boolean = false,
    val pluginConfig: PluginConfiguration = PluginConfiguration(),
    val pluginsFile: String? = null,
    val jenkinsMetadataFile: String? = null,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(JenkinsConfiguration::class.java)

        /**
         * Parses Jenkins configuration from a map.
         */
        @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): JenkinsConfiguration = try {
            JenkinsConfiguration(
                filePatterns = parseFilePatterns(map),
                sharedLibraries = parseSharedLibraries(map),
                gdslPaths = parseGdslPaths(map),
                gdslExecutionEnabled = parseGdslExecutionEnabled(map),
                pluginConfig = PluginConfiguration.fromMap(map),
                pluginsFile = map["jenkins.plugins.file"] as? String,
                jenkinsMetadataFile = map["jenkins.metadata.file"] as? String,
            )
        } catch (e: Exception) {
            logger.warn("Error parsing Jenkins configuration, using defaults", e)
            JenkinsConfiguration()
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseFilePatterns(map: Map<String, Any>): List<String> {
            val patterns = map["jenkins.filePatterns"] as? List<*>
            return patterns?.mapNotNull { it as? String } ?: listOf("Jenkinsfile")
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseSharedLibraries(map: Map<String, Any>): List<SharedLibrary> {
            val libraries = map["jenkins.sharedLibraries"] as? List<*> ?: return emptyList()

            return libraries.mapNotNull { lib ->
                try {
                    val libMap = lib as? Map<String, Any> ?: return@mapNotNull null
                    SharedLibrary(
                        name = libMap["name"] as? String ?: return@mapNotNull null,
                        jar = libMap["jar"] as? String ?: return@mapNotNull null,
                        sourcesJar = libMap["sourcesJar"] as? String,
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to parse shared library entry: $lib", e)
                    null
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseGdslPaths(map: Map<String, Any>): List<String> {
            val paths = map["jenkins.gdslPaths"] as? List<*>
            return paths?.mapNotNull { it as? String } ?: emptyList()
        }

        private fun parseGdslExecutionEnabled(map: Map<String, Any>): Boolean =
            map["jenkins.gdslExecution.enabled"] as? Boolean ?: false
    }
}

/**
 * Represents a Jenkins shared library configuration.
 */
data class SharedLibrary(val name: String, val jar: String, val sourcesJar: String? = null)
