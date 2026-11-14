// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

@Serializable
private data class ClassFileVersions(val majorVersionToJdk: Map<String, Int>)

/**
 * Service for loading and querying Gradle/JDK compatibility data from JSON resources.
 * Externalizes version mappings to allow updates without code changes.
 */
class GradleJdkCompatibilityService internal constructor(private val resourceLoader: (String) -> InputStream?) {
    constructor() : this({ GradleJdkCompatibilityService::class.java.getResourceAsStream(it) })

    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private val majorVersionToJdk: Map<Int, Int> by lazy { loadMajorVersionMapping() }
    private val runtimeCompatibility = GradleRuntimeCompatibility(resourceLoader)

    /**
     * Maps class file major version to JDK version.
     * Falls back to formula (majorVersion - 44) for unknown versions.
     */
    fun majorVersionToJdk(majorVersion: Int): Int = majorVersionToJdk[majorVersion] ?: (majorVersion - 44)

    /**
     * Gets the minimum Gradle version required for a given JDK version.
     * Returns null if the JDK is absent from the published matrix or the data is unavailable.
     */
    fun minGradleVersionForJdk(jdkVersion: Int): String? = runtimeCompatibility.minimum(jdkVersion)

    /** Returns the exclusive upper bound for a known JDK, if documented. */
    fun maxGradleVersionExclusiveForJdk(jdkVersion: Int): String? = runtimeCompatibility.maximumExclusive(jdkVersion)

    private fun loadMajorVersionMapping(): Map<Int, Int> {
        return try {
            val stream = resourceLoader("/class-file-versions.json")
            if (stream == null) {
                logger.warn { "class-file-versions.json not found, using fallback formula" }
                return emptyMap()
            }

            val content = stream.bufferedReader().use { it.readText() }
            val mapping = json.decodeFromString<ClassFileVersions>(content).majorVersionToJdk
            mapping.entries.associate { (key, value) ->
                key.toInt() to value
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load class-file-versions.json: ${e.message}" }
            emptyMap()
        }
    }

    companion object {
        /** Singleton instance for shared use. */
        val instance: GradleJdkCompatibilityService by lazy { GradleJdkCompatibilityService() }
    }
}
