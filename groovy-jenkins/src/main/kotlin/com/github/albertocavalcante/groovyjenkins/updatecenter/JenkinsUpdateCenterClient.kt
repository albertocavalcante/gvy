package com.github.albertocavalcante.groovyjenkins.updatecenter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the Jenkins Update Center API.
 *
 * The Update Center provides metadata about all available Jenkins plugins,
 * including their Maven coordinates, dependencies, and versions.
 *
 * See: https://updates.jenkins.io/
 */
class JenkinsUpdateCenterClient(
    private val updateCenterUrl: String = DEFAULT_UPDATE_CENTER_URL,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {

    private val logger = LoggerFactory.getLogger(JenkinsUpdateCenterClient::class.java)

    /**
     * Plugin metadata from the Update Center.
     */
    data class PluginInfo(
        val name: String,
        val version: String,
        val gav: String, // Maven GAV string
        val url: String?, // Download URL
        val requiredCore: String?, // Minimum Jenkins version
        val dependencies: List<PluginDependency> = emptyList(),
    ) {
        /**
         * Parse the GAV string into Maven coordinates.
         * Format: groupId:artifactId:version
         */
        fun toMavenCoordinates(): Triple<String, String, String>? {
            val parts = gav.split(":")
            return if (parts.size >= 3) {
                Triple(parts[0], parts[1], parts[2])
            } else {
                null
            }
        }
    }

    data class PluginDependency(val name: String, val version: String, val optional: Boolean = false)

    companion object {
        const val DEFAULT_UPDATE_CENTER_URL = "https://updates.jenkins.io/current/update-center.actual.json"

        // Fallback for plugin GAV resolution
        // TODO: Could be replaced with actual Update Center lookup
        private val KNOWN_PLUGIN_GAVS = mapOf(
            "kubernetes" to "org.csanchez.jenkins.plugins:kubernetes",
            "docker-workflow" to "org.jenkinsci.plugins:docker-workflow",
            "workflow-step-api" to "org.jenkins-ci.plugins.workflow:workflow-step-api",
            "workflow-basic-steps" to "org.jenkins-ci.plugins.workflow:workflow-basic-steps",
            "workflow-durable-task-step" to "org.jenkins-ci.plugins.workflow:workflow-durable-task-step",
            "workflow-cps" to "org.jenkins-ci.plugins.workflow:workflow-cps",
            "workflow-scm-step" to "org.jenkins-ci.plugins.workflow:workflow-scm-step",
            "workflow-job" to "org.jenkins-ci.plugins.workflow:workflow-job",
            "credentials-binding" to "org.jenkins-ci.plugins:credentials-binding",
            "credentials" to "org.jenkins-ci.plugins:credentials",
            "git" to "org.jenkins-ci.plugins:git",
            "git-client" to "org.jenkins-ci.plugins:git-client",
            "ssh-agent" to "org.jenkins-ci.plugins:ssh-agent",
            "pipeline-utility-steps" to "org.jenkins-ci.plugins:pipeline-utility-steps",
            "pipeline-stage-step" to "org.jenkins-ci.plugins:pipeline-stage-step",
            "pipeline-input-step" to "org.jenkins-ci.plugins:pipeline-input-step",
            "pipeline-build-step" to "org.jenkins-ci.plugins:pipeline-build-step",
            "slack" to "org.jenkins-ci.plugins:slack",
            "email-ext" to "org.jenkins-ci.plugins:email-ext",
            "junit" to "org.jenkins-ci.plugins:junit",
            "lockable-resources" to "org.6wind.jenkins:lockable-resources",
            "ansicolor" to "org.jenkins-ci.plugins:ansicolor",
            "timestamper" to "org.jenkins-ci.plugins:timestamper",
            "badge" to "org.jenkins-ci.plugins:badge",
            "http-request" to "org.jenkins-ci.plugins:http_request",
        )
    }

    /**
     * Look up Maven coordinates for a plugin by short name.
     *
     * Uses static mapping for known plugins. For a dynamic solution,
     * would need to fetch and parse the full Update Center JSON.
     *
     * @param pluginName Short plugin name (e.g., "kubernetes", "git")
     * @param version Version string
     * @return Triple of (groupId, artifactId, version) or null
     */
    fun resolvePluginCoordinates(pluginName: String, version: String): Triple<String, String, String>? {
        val gav = KNOWN_PLUGIN_GAVS[pluginName]
        return if (gav != null) {
            val parts = gav.split(":")
            if (parts.size == 2) {
                Triple(parts[0], parts[1], version)
            } else {
                null
            }
        } else {
            // Fallback: assume org.jenkins-ci.plugins group
            logger.debug("Using fallback GAV for unknown plugin: {}", pluginName)
            Triple("org.jenkins-ci.plugins", pluginName, version)
        }
    }

    /**
     * Fetch plugin info from the Update Center.
     *
     * NOTE: The Update Center JSON is large (~5MB). For production use,
     * consider caching or using the plugin-specific API endpoints.
     *
     * TODO: Implement full Update Center JSON parsing
     */
    suspend fun fetchPluginInfo(pluginName: String): PluginInfo? {
        // For now, use static mapping
        logger.debug("Plugin info lookup for: {} (using static mapping)", pluginName)

        val gav = KNOWN_PLUGIN_GAVS[pluginName] ?: return null
        val parts = gav.split(":")

        return if (parts.size == 2) {
            PluginInfo(
                name = pluginName,
                version = "latest", // Would be fetched from Update Center
                gav = "$gav:latest",
                url = null,
                requiredCore = null,
            )
        } else {
            null
        }
    }

    /**
     * Ping the Update Center to check connectivity.
     */
    suspend fun checkConnectivity(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(updateCenterUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() in 200..299
        } catch (e: Exception) {
            logger.debug("Update Center not reachable: {}", e.message)
            false
        }
    }
}
