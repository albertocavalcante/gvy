// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.SharedLibraryConfigurationDiagnostic.Code
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedLibraryConfigurationDiagnosticsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("sourceShapes")
    fun `source-only entries report disabled support without interpreting their values`(
        description: String,
        source: Any?,
    ) {
        val result = parse(mapOf("name" to "typed", "source" to source))

        assertTrue(result.libraries.isEmpty(), description)
        assertEquals(
            listOf(SharedLibraryConfigurationDiagnostic(0, Code.TYPED_SOURCE_DISABLED)),
            result.diagnostics,
            description,
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFieldConflicts")
    fun `legacy field presence conflicts with source even when the field value is null`(
        description: String,
        field: String,
        value: Any?,
    ) {
        val result = parse(mapOf("name" to "typed", "source" to null, field to value))

        assertTrue(result.libraries.isEmpty(), description)
        assertEquals(
            listOf(SharedLibraryConfigurationDiagnostic(0, Code.CONFLICTING_FORMATS)),
            result.diagnostics,
            description,
        )
    }

    @Test
    fun `diagnostics retain original indices and valid sibling settings`() {
        val entries = listOf(
            "not an entry",
            mapOf("name" to "legacy", "jar" to "legacy.jar"),
            mapOf("name" to "typed", "source" to mapOf("kind" to "git")),
            mapOf("name" to "incomplete"),
            mapOf("name" to "conflict", "source" to null, "jar" to "must-not-activate.jar", "sourcesJar" to null),
        )
        val result = JenkinsConfiguration.parseSharedLibraryConfiguration(entries)
        val configuration = JenkinsConfiguration.fromMap(
            mapOf(
                "jenkins.filePatterns" to listOf("CustomJenkinsfile"),
                "jenkins.gdslPaths" to listOf("steps.gdsl"),
                "jenkins.sharedLibraries" to entries,
            ),
        )

        assertEquals(listOf(SharedLibrary("legacy", "legacy.jar")), configuration.sharedLibraries)
        assertEquals(configuration.sharedLibraries, result.libraries)
        assertEquals(listOf("CustomJenkinsfile"), configuration.filePatterns)
        assertEquals(listOf("steps.gdsl"), configuration.gdslPaths)
        assertEquals(
            listOf(
                SharedLibraryConfigurationDiagnostic(2, Code.TYPED_SOURCE_DISABLED),
                SharedLibraryConfigurationDiagnostic(4, Code.CONFLICTING_FORMATS),
            ),
            result.diagnostics,
        )
    }

    @Test
    fun `diagnostic text contains only the index and fixed problem description`() {
        val rawValue = object {
            override fun toString(): String = error("A source value must not be formatted")
        }
        val result = parse(mapOf("name" to rawValue, "jar" to rawValue, "source" to rawValue))
        assertEquals(1, result.diagnostics.size)
        val diagnostic = result.diagnostics.single()

        assertEquals("jenkins.sharedLibraries[0]", diagnostic.settingPath)
        assertEquals(Code.CONFLICTING_FORMATS, diagnostic.code)
        assertEquals(
            "A source declaration cannot be combined with jar or sourcesJar; entry is inactive.",
            diagnostic.message,
        )
    }

    @Test
    fun `an unreadable entry retains valid siblings and a static diagnostic`() {
        val unreadableEntry = object : Map<String, Any> by emptyMap() {
            override fun containsKey(key: String): Boolean = error("private configuration contents")
        }
        val result = parse(unreadableEntry, mapOf("name" to "legacy", "jar" to "legacy.jar"))

        assertEquals(listOf(SharedLibrary("legacy", "legacy.jar")), result.libraries)
        assertEquals(
            listOf(SharedLibraryConfigurationDiagnostic(0, Code.UNREADABLE_ENTRY)),
            result.diagnostics,
        )
        assertEquals(
            "Library configuration entry could not be read; entry is inactive.",
            result.diagnostics.single().message,
        )
    }

    @Test
    fun `fatal errors are not converted into configuration diagnostics`() {
        val failure = AssertionError("fatal test failure")
        val entry = object : Map<String, Any> by emptyMap() {
            override fun get(key: String): Any? = throw failure
        }

        val thrown = assertFailsWith<AssertionError> { parse(entry) }

        assertSame(failure, thrown)
    }

    private fun parse(vararg entries: Any): SharedLibraryConfigurationParseResult =
        JenkinsConfiguration.parseSharedLibraryConfiguration(entries.toList())

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

        @JvmStatic
        fun legacyFieldConflicts(): Stream<Arguments> = Stream.of(
            Arguments.of("null jar", "jar", null),
            Arguments.of("non-string jar", "jar", false),
            Arguments.of("string jar", "jar", "must-not-activate.jar"),
            Arguments.of("null sourcesJar", "sourcesJar", null),
            Arguments.of("non-string sourcesJar", "sourcesJar", false),
            Arguments.of("string sourcesJar", "sourcesJar", "must-not-activate-sources.jar"),
        )
    }
}
