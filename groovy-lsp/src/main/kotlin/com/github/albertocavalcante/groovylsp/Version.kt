package com.github.albertocavalcante.groovylsp

import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Properties

/**
 * Provides access to build-time version information.
 * Version is determined at build time from environment variables and build context.
 */
object Version {

    private val logger = LoggerFactory.getLogger(Version::class.java)

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
                logger.warn("Could not determine version from build properties, using fallback")
            } else {
                logger.debug("Loaded version: $version")
            }
        }
    }

    /**
     * The base version without any snapshot suffix.
     */
    val base: String by lazy {
        properties.getProperty("baseVersion", "unknown")
    }

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
            logger.warn("{} not found in classpath", resourceName)
            return properties
        }

        try {
            inputStream.use(properties::load)
            logger.debug("Successfully loaded version properties")
        } catch (e: IOException) {
            logger.error("Failed to read {}", resourceName, e)
        } catch (e: IllegalArgumentException) {
            logger.error("Failed to parse {}", resourceName, e)
        }

        return properties
    }
}
