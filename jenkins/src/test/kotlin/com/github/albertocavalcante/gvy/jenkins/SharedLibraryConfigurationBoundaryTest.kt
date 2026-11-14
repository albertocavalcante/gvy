// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.plugins.PluginConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedLibraryConfigurationBoundaryTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("sourceShapes")
    fun `a source discriminator cannot fall through to legacy jar selection`(description: String, source: Any?) {
        val configuration = parse(
            mapOf("name" to "typed", "jar" to "must-not-activate.jar", "source" to source),
        )

        assertTrue(configuration.sharedLibraries.isEmpty(), description)
    }

    @Test
    fun `a typed entry cannot hide a legacy entry with the same alias`() {
        val configuration = parse(
            mapOf("name" to "legacy", "jar" to "legacy.jar", "sourcesJar" to "legacy-sources.jar"),
            mapOf("name" to "legacy", "jar" to "must-not-activate.jar", "source" to mapOf("kind" to "directory")),
        )

        assertEquals(listOf(SharedLibrary("legacy", "legacy.jar", "legacy-sources.jar")), configuration.sharedLibraries)
    }

    @Test
    fun `legacy optional and unknown fields retain their previous interpretation`() {
        val configuration = parse(
            mapOf("name" to "legacy", "jar" to "legacy.jar", "sourcesJar" to false, "implicit" to false),
            mapOf("name" to "incomplete"),
            "not an entry",
            mapOf("name" to 42, "jar" to "invalid.jar"),
        )

        assertEquals(listOf(SharedLibrary("legacy", "legacy.jar")), configuration.sharedLibraries)
    }

    @Test
    fun `legacy constructors retain their positional source contract`() {
        val library = SharedLibrary("legacy", "legacy.jar", "legacy-sources.jar")
        val plugins = PluginConfiguration()
        val configuration = JenkinsConfiguration(
            listOf("CustomJenkinsfile"),
            listOf(library),
            listOf("steps.gdsl"),
            true,
            plugins,
            "plugins.txt",
            "metadata.json",
        )

        assertEquals(listOf("CustomJenkinsfile"), configuration.filePatterns)
        assertEquals(listOf(library), configuration.sharedLibraries)
        assertEquals(listOf("steps.gdsl"), configuration.gdslPaths)
        assertTrue(configuration.gdslExecutionEnabled)
        assertEquals(plugins, configuration.pluginConfig)
        assertEquals("plugins.txt", configuration.pluginsFile)
        assertEquals("metadata.json", configuration.jenkinsMetadataFile)
    }

    private fun parse(vararg entries: Any): JenkinsConfiguration = JenkinsConfiguration.fromMap(
        mapOf("jenkins.sharedLibraries" to entries.toList()),
    )

    companion object {
        @JvmStatic
        fun sourceShapes(): Stream<Arguments> = Stream.of(
            Arguments.of("null source", null),
            Arguments.of("non-object source", "directory"),
            Arguments.of("empty source object", emptyMap<String, Any>()),
            Arguments.of("directory source", mapOf("kind" to "directory", "path" to "library")),
            Arguments.of("git source", mapOf("kind" to "git", "remote" to "https://git.example.com/library.git")),
            Arguments.of("unknown source kind", mapOf("kind" to "future")),
        )
    }
}
