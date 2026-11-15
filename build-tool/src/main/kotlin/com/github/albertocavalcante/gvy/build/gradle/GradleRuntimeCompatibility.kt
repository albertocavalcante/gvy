// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.util.GradleVersion
import java.io.InputStream

/** Whether the published Gradle runtime matrix covers this pair. */
enum class GradleRuntimeSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

@Serializable
private data class RuntimeMatrix(val compatibility: List<RuntimeEntry>)

@Serializable
private data class RuntimeEntry(val jdk: Int, val minGradle: String?, val maxGradleExclusive: String? = null)

internal class GradleRuntimeCompatibility(private val resourceLoader: (String) -> InputStream?) {
    private val entries: Map<Int, RuntimeEntry> by lazy {
        try {
            val stream = resourceLoader("/gradle-jdk-compatibility.json") ?: return@lazy emptyMap()
            val content = stream.bufferedReader().use { it.readText() }
            val rows = json.decodeFromString<RuntimeMatrix>(content).compatibility
            require(rows.map { it.jdk }.distinct().size == rows.size)
            rows.forEach { row ->
                require(row.jdk > 0)
                val minimum = row.minGradle?.let(GradleVersion::version)
                val maximum = row.maxGradleExclusive?.let(GradleVersion::version)
                require(maximum == null || (minimum != null && maximum > minimum))
            }
            rows.associateBy { it.jdk }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun minimum(jdk: Int): String? = entries[jdk]?.minGradle

    fun maximumExclusive(jdk: Int): String? = entries[jdk]?.maxGradleExclusive

    fun check(gradle: String, jdk: Int): GradleRuntimeSupport {
        val entry = entries[jdk] ?: return GradleRuntimeSupport.UNKNOWN
        val minimum = entry.minGradle ?: return GradleRuntimeSupport.UNKNOWN
        return try {
            val version = GradleVersion.version(gradle)
            if (version != version.baseVersion) return GradleRuntimeSupport.UNKNOWN
            if (version > GradleVersion.version("9.9.1")) return GradleRuntimeSupport.UNKNOWN
            val min = GradleVersion.version(minimum)
            val max = entry.maxGradleExclusive?.let(GradleVersion::version)
            if (version < min || (max != null && version.baseVersion >= max)) {
                GradleRuntimeSupport.UNSUPPORTED
            } else {
                GradleRuntimeSupport.SUPPORTED
            }
        } catch (_: IllegalArgumentException) {
            GradleRuntimeSupport.UNKNOWN
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
