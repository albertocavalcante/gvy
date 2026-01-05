package com.github.albertocavalcante.groovyjenkins.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Downloads Jenkins plugins from the Jenkins releases repository.
 *
 * URL pattern: https://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins/{id}/{version}/{id}-{version}.hpi
 *
 * Plugins are cached in the specified cache directory to avoid repeated downloads.
 */
class PluginDownloader(
    private val cacheDir: Path,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json()
        }
        followRedirects = true
    },
) {
    private val logger = LoggerFactory.getLogger(PluginDownloader::class.java)

    companion object {
        private const val JENKINS_RELEASES_BASE = "https://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 300_000L
    }

    /**
     * Downloads a plugin, using cache if available.
     *
     * @param pluginId Plugin identifier (e.g., "workflow-basic-steps")
     * @param version Plugin version (e.g., "1058.vcb_fc1e3a_21a_9")
     * @return Path to the downloaded .hpi file
     * @throws PluginDownloadException if download fails
     */
    suspend fun download(pluginId: String, version: String): Path {
        val cachedPath = cacheDir.resolve("$pluginId-$version.hpi")

        if (Files.exists(cachedPath)) {
            logger.debug("Using cached plugin: {}", cachedPath)
            return cachedPath
        }

        Files.createDirectories(cacheDir)

        val urls = buildUrls(pluginId, version)
        var lastFailure: Throwable? = null

        for (url in urls) {
            logger.info("Trying URL: {}", url)
            val attempt = runCatching {
                httpClient.prepareRequest(url).execute { response ->
                    if (response.status != HttpStatusCode.OK) {
                        logger.debug("URL returned HTTP {}: {}", response.status, url)
                        return@execute
                    }

                    val tempFile = Files.createTempFile(cacheDir, "$pluginId-", ".hpi.tmp")

                    runCatching {
                        // Use toInputStream to bridge Ktor channel to Java IO
                        val channel = response.bodyAsChannel()
                        channel.toInputStream().use { input ->
                            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
                        }
                        Files.move(tempFile, cachedPath, StandardCopyOption.REPLACE_EXISTING)
                        logger.info("Downloaded plugin to: {}", cachedPath)
                    }.onFailure {
                        runCatching { Files.deleteIfExists(tempFile) }
                    }.getOrThrow()
                }

                Files.exists(cachedPath)
            }

            val failure = attempt.exceptionOrNull()
            if (failure is CancellationException || failure is Error) throw failure
            if (failure != null) {
                logger.debug("Failed to download from {}: {}", url, failure.message)
                lastFailure = failure
                continue
            }

            if (attempt.getOrDefault(false)) return cachedPath
        }

        throw PluginDownloadException(
            "Failed to download $pluginId:$version from any URL. Tried: ${urls.joinToString()}",
            lastFailure,
        )
    }

    /**
     * Downloads all plugins from a parsed plugins list.
     *
     * @param plugins List of PluginSpec objects from PluginsParser
     * @return Map of plugin ID to downloaded file path
     */
    suspend fun downloadAll(plugins: List<PluginSpec>): Map<String, Path> = plugins.associate { plugin ->
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
}

/**
 * Exception thrown when plugin download fails.
 */
class PluginDownloadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
