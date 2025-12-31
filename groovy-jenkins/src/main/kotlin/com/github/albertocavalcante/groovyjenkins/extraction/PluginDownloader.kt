package com.github.albertocavalcante.groovyjenkins.extraction

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Downloads Jenkins plugins from the Jenkins releases repository.
 *
 * URL pattern: https://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins/{id}/{version}/{id}-{version}.hpi
 *
 * Plugins are cached in the specified cache directory to avoid repeated downloads.
 */
class PluginDownloader(
    private val cacheDir: Path,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {
    private val logger = LoggerFactory.getLogger(PluginDownloader::class.java)

    companion object {
        private const val JENKINS_RELEASES_BASE = "https://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins"
    }

    /**
     * Downloads a plugin, using cache if available.
     *
     * @param pluginId Plugin identifier (e.g., "workflow-basic-steps")
     * @param version Plugin version (e.g., "1058.vcb_fc1e3a_21a_9")
     * @return Path to the downloaded .hpi file
     * @throws PluginDownloadException if download fails
     */
    fun download(pluginId: String, version: String): Path {
        val cachedPath = cacheDir.resolve("$pluginId-$version.hpi")

        if (Files.exists(cachedPath)) {
            logger.debug("Using cached plugin: {}", cachedPath)
            return cachedPath
        }

        Files.createDirectories(cacheDir)

        val urls = buildUrls(pluginId, version)
        var lastException: Exception? = null

        for (url in urls) {
            logger.info("Trying URL: {}", url)
            try {
                val response = tryDownload(url)

                if (response.statusCode() == 200) {
                    val tempFile = Files.createTempFile(cacheDir, "$pluginId-", ".hpi.tmp")
                    try {
                        response.body().use { input ->
                            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
                        }
                        Files.move(tempFile, cachedPath, StandardCopyOption.ATOMIC_MOVE)
                        logger.info("Downloaded plugin to: {}", cachedPath)
                        return cachedPath
                    } catch (e: Exception) {
                        Files.deleteIfExists(tempFile)
                        throw e
                    }
                }
                logger.debug("URL returned HTTP {}: {}", response.statusCode(), url)
            } catch (e: Exception) {
                logger.debug("Failed to download from {}: {}", url, e.message)
                lastException = e
            }
        }

        throw PluginDownloadException(
            "Failed to download $pluginId:$version from any URL. Tried: ${urls.joinToString()}",
            lastException,
        )
    }

    /**
     * Downloads all plugins from a parsed plugins list.
     *
     * @param plugins List of PluginSpec objects from PluginsParser
     * @return Map of plugin ID to downloaded file path
     */
    fun downloadAll(plugins: List<PluginSpec>): Map<String, Path> = plugins.associate { plugin ->
        plugin.id to download(plugin.id, plugin.version)
    }

    /**
     * Builds candidate URLs for a plugin. Some plugins (like workflow-*) have
     * non-standard Maven coordinates, so we try multiple patterns.
     */
    private fun buildUrls(pluginId: String, version: String): List<String> {
        val urls = mutableListOf<String>()

        // Standard pattern: org/jenkins-ci/plugins/{id}/{version}/{id}-{version}.hpi
        urls.add("$JENKINS_RELEASES_BASE/$pluginId/$version/$pluginId-$version.hpi")

        // Workflow plugins: org/jenkins-ci/plugins/workflow/{id}/{version}/{id}-{version}.hpi
        if (pluginId.startsWith("workflow-")) {
            urls.add("$JENKINS_RELEASES_BASE/workflow/$pluginId/$version/$pluginId-$version.hpi")
        }

        // Pipeline plugins: org/jenkins-ci/plugins/pipeline/{id}/{version}/{id}-{version}.hpi
        if (pluginId.startsWith("pipeline-")) {
            urls.add("$JENKINS_RELEASES_BASE/pipeline/$pluginId/$version/$pluginId-$version.hpi")
        }

        return urls
    }

    private fun tryDownload(url: String): HttpResponse<java.io.InputStream> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }
}

/**
 * Exception thrown when plugin download fails.
 */
class PluginDownloadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
