package com.github.albertocavalcante.gvy.build.gradle

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Service for loading and querying Gradle/JDK compatibility data from JSON resources.
 * Externalizes version mappings to allow updates without code changes.
 */
class GradleJdkCompatibilityService {
    private val logger = KotlinLogging.logger {}
    private val gson = Gson()

    private val majorVersionToJdk: Map<Int, Int> by lazy { loadMajorVersionMapping() }
    private val jdkToMinGradle: Map<Int, String> by lazy { loadJdkCompatibility() }

    /**
     * Maps class file major version to JDK version.
     * Falls back to formula (majorVersion - 44) for unknown versions.
     */
    fun majorVersionToJdk(majorVersion: Int): Int = majorVersionToJdk[majorVersion] ?: (majorVersion - 44)

    /**
     * Gets the minimum Gradle version required for a given JDK version.
     * Returns null if JDK is compatible with very old Gradle versions.
     */
    fun minGradleVersionForJdk(jdkVersion: Int): String? =
        jdkToMinGradle[jdkVersion] ?: inferMinGradleVersion(jdkVersion)

    private fun inferMinGradleVersion(jdkVersion: Int): String? = when {
        jdkVersion <= 8 -> null
        jdkVersion > 25 -> "9.1" // Future JDKs likely need latest Gradle
        else -> null
    }

    private fun loadMajorVersionMapping(): Map<Int, Int> {
        return try {
            val stream = javaClass.getResourceAsStream("/class-file-versions.json")
            if (stream == null) {
                logger.warn { "class-file-versions.json not found, using fallback formula" }
                return emptyMap()
            }

            val json = stream.bufferedReader().use { it.readText() }
            val root = gson.fromJson(json, JsonObject::class.java)
            val mapping = root.getAsJsonObject("majorVersionToJdk")

            mapping.entrySet().associate { (key, value) ->
                key.toInt() to value.asInt
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load class-file-versions.json: ${e.message}" }
            emptyMap()
        }
    }

    private fun loadJdkCompatibility(): Map<Int, String> {
        return try {
            val stream = javaClass.getResourceAsStream("/gradle-jdk-compatibility.json")
            if (stream == null) {
                logger.warn { "gradle-jdk-compatibility.json not found, using fallback mappings" }
                return emptyMap()
            }

            val json = stream.bufferedReader().use { it.readText() }
            val root = gson.fromJson(json, JsonObject::class.java)
            val compatibility = root.getAsJsonArray("compatibility")

            compatibility.mapNotNull { element ->
                val obj = element.asJsonObject
                val jdk = obj.get("jdk").asInt
                val minGradle = obj.get("minGradle")
                if (minGradle.isJsonNull) null else jdk to minGradle.asString
            }.toMap()
        } catch (e: Exception) {
            logger.warn { "Failed to load gradle-jdk-compatibility.json: ${e.message}" }
            emptyMap()
        }
    }

    companion object {
        /** Singleton instance for shared use. */
        val instance: GradleJdkCompatibilityService by lazy { GradleJdkCompatibilityService() }
    }
}
