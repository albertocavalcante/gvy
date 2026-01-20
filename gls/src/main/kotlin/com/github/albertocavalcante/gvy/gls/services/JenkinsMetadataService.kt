package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.build.MavenSourceArtifactResolver
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import com.github.albertocavalcante.gvy.jenkins.JenkinsPluginManager
import com.github.albertocavalcante.gvy.jenkins.extraction.PluginDownloader
import com.github.albertocavalcante.gvy.jenkins.extraction.PluginsParser
import com.github.albertocavalcante.gvy.jenkins.metadata.JsonMetadataLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Service to manage Jenkins metadata at startup.
 *
 * Responsibilities:
 * - Load plugin list from configuration
 * - Download plugins via PluginDownloader
 * - Register plugins with JenkinsPluginManager
 */
class JenkinsMetadataService(
    private val pluginManager: JenkinsPluginManager,
    private val configuration: JenkinsConfiguration,
    private val pluginDownloader: PluginDownloader = PluginDownloader(
        MavenSourceArtifactResolver.getDefaultCacheDir().parent.resolve("jenkins-plugins"),
    ),
) {
    private val logger = KotlinLogging.logger {}

    private fun rethrowIfCancellationOrError(throwable: Throwable) {
        when (throwable) {
            is CancellationException -> throw throwable
            is Error -> throw throwable
        }
    }

    suspend fun initialize() {
        val pluginsFileStr = configuration.pluginsFile
        if (pluginsFileStr.isNullOrBlank()) {
            logger.debug { "No plugins file configured" }
            return
        }

        val pluginsFile = Path(pluginsFileStr)
        if (!Files.exists(pluginsFile)) {
            logger.warn { "Configured plugins file does not exist: $pluginsFile" }
            return
        }

        logger.info { "Loading Jenkins plugins from: $pluginsFile" }

        val plugins =
            runCatching { PluginsParser.parse(pluginsFile) }
                .onFailure { throwable ->
                    rethrowIfCancellationOrError(throwable)
                    logger.error(throwable) { "Failed to parse plugins file: $pluginsFile" }
                }
                .getOrNull()

        if (plugins != null) {
            plugins.forEach { plugin ->
                runCatching {
                    logger.debug { "Downloading plugin: ${plugin.id}" }
                    // download is now a suspend function, which is fine since initialize is suspend
                    val jarPath = pluginDownloader.download(plugin.id, plugin.version)
                    pluginManager.registerPluginJar(plugin.id, jarPath)
                }
                    .onFailure { throwable ->
                        rethrowIfCancellationOrError(throwable)
                        logger.error(throwable) { "Failed to load plugin: ${plugin.id}" }
                    }
            }

            logger.info { "Loaded ${plugins.size} plugins from $pluginsFile" }
        }

        initializeStaticMetadata()
    }

    private suspend fun initializeStaticMetadata() {
        val metadataFileStr = configuration.jenkinsMetadataFile
        if (metadataFileStr.isNullOrBlank()) return

        val metadataFile = Path(metadataFileStr)
        if (!Files.exists(metadataFile)) {
            logger.warn { "Configured metadata file does not exist: $metadataFile" }
            return
        }

        runCatching {
            logger.info { "Loading static Jenkins metadata from: $metadataFile" }
            val metadata = JsonMetadataLoader().load(metadataFile)
            pluginManager.registerStaticMetadata(metadata)
            logger.info { "Successfully registered static Jenkins metadata" }
        }
            .onFailure { throwable ->
                rethrowIfCancellationOrError(throwable)
                logger.error(throwable) { "Failed to load static metadata from: $metadataFile" }
            }
    }
}
