package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration
import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovyjenkins.extraction.PluginDownloader
import com.github.albertocavalcante.groovyjenkins.extraction.PluginsParser
import com.github.albertocavalcante.groovylsp.buildtool.MavenSourceArtifactResolver
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun initialize() {
        val pluginsFileStr = configuration.pluginsFile
        if (pluginsFileStr.isNullOrBlank()) {
            logger.debug("No plugins file configured")
            return
        }

        val pluginsFile = Path(pluginsFileStr)
        if (!Files.exists(pluginsFile)) {
            logger.warn("Configured plugins file does not exist: {}", pluginsFile)
            return
        }

        logger.info("Loading Jenkins plugins from: {}", pluginsFile)

        try {
            val plugins = PluginsParser.parse(pluginsFile)

            plugins.forEach { plugin ->
                try {
                    logger.debug("Downloading plugin: {}", plugin.id)
                    val jarPath = pluginDownloader.download(plugin.id, plugin.version)
                    pluginManager.registerPluginJar(plugin.id, jarPath)
                } catch (e: Exception) {
                    logger.error("Failed to load plugin: ${plugin.id}", e)
                }
            }

            logger.info("Loaded {} plugins from {}", plugins.size, pluginsFile)
        } catch (e: Exception) {
            logger.error("Failed to parse plugins file: {}", pluginsFile, e)
        }

        initializeStaticMetadata()
    }

    private suspend fun initializeStaticMetadata() {
        val metadataFileStr = configuration.jenkinsMetadataFile
        if (metadataFileStr.isNullOrBlank()) return

        val metadataFile = Path(metadataFileStr)
        if (!Files.exists(metadataFile)) {
            logger.warn("Configured metadata file does not exist: {}", metadataFile)
            return
        }

        try {
            logger.info("Loading static Jenkins metadata from: {}", metadataFile)
            // Lazy load JsonMetadataLoader to avoid unnecessary class loading if not used
            val metadata = com.github.albertocavalcante.groovyjenkins.metadata.JsonMetadataLoader().load(metadataFile)
            pluginManager.registerStaticMetadata(metadata)
            logger.info("Successfully registered static Jenkins metadata")
        } catch (e: Exception) {
            logger.error("Failed to load static metadata from: {}", metadataFile, e)
        }
    }
}
