package com.github.albertocavalcante.gvy.gls

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.Properties

/**
 * Provides access to build-time version information.
 * Version is determined at build time from environment variables and build context.
 */
object Version {

    private val logger = KotlinLogging.logger {}

    private val properties: Properties by lazy {
        loadVersionProperties()
    }

    /**
     * The current version of the Groovy Language Server.
     * Falls back to "unknown" if version properties cannot be loaded.
     */
    val current: String by lazy {
        properties.getProperty("version", "unknown").also { version ->
            if (version == "unknown") {
                logger.warn { "Could not determine version from build properties, using fallback" }
            } else {
                logger.debug { "Loaded version: $version" }
            }
        }
    }

    /**
     * Full build provenance (commit, dirty state, times). Prefer this over reading raw properties.
     */
    val info: BuildInfo by lazy { BuildInfo.fromProperties(properties) }

    /**
     * The base version without any snapshot suffix.
     */
    val base: String by lazy { info.baseVersion }

    /**
     * Human-facing version, e.g. `0.5.4-dev+g6c6f023.dirty` for development builds and `0.5.4` for releases.
     * This is what the server reports to clients as `serverInfo.version`.
     */
    val display: String by lazy { info.displayVersion }

    /**
     * Whether this is a snapshot/development build.
     */
    val isSnapshot: Boolean by lazy {
        current.contains("SNAPSHOT")
    }

    private fun loadVersionProperties(): Properties {
        val properties = Properties()
        val resourceName = "version.properties"
        val inputStream = Version::class.java.classLoader.getResourceAsStream(resourceName)

        if (inputStream == null) {
            logger.warn { "$resourceName not found in classpath" }
            return properties
        }

        try {
            inputStream.use(properties::load)
            logger.debug { "Successfully loaded version properties" }
        } catch (e: IOException) {
            logger.error(e) { "Failed to read $resourceName" }
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Failed to parse $resourceName" }
        }

        return properties
    }
}
