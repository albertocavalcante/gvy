// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.stream.Stream
import kotlin.test.assertEquals

class LegacySharedLibrarySelectionTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacySelections")
    fun `legacy classpath selection retains its original reference projection`(
        description: String,
        source: String,
        expectedNames: List<String>,
    ) {
        val workspace = Files.createDirectory(temporaryRoot.resolve("workspace"))
        val libraryPaths = listOf("first", "second").associateWith { name ->
            workspace.resolve("$name.jar").also { path ->
                JarOutputStream(Files.newOutputStream(path)).use { }
            }
        }
        val configuration = JenkinsConfiguration(
            sharedLibraries = libraryPaths.map { (name, path) -> SharedLibrary(name, path.toString()) },
        )
        val context = JenkinsContext(
            configuration,
            workspace,
            stubsCacheRoot = temporaryRoot.resolve("stub-cache"),
        )
        val references = context.parseLibraries(source)

        val selected = context.buildClasspath(references).filter { it in libraryPaths.values }

        assertEquals(expectedNames.map(libraryPaths::getValue), selected, description)
    }

    companion object {
        @JvmStatic
        fun legacySelections(): Stream<Arguments> = Stream.of(
            Arguments.of("no references selects all legacy entries", "echo 'hello'", listOf("first", "second")),
            Arguments.of("annotation selects its name", "@Library('first') _", listOf("first")),
            Arguments.of(
                "version text does not change legacy selection",
                "@Library('first@release') _",
                listOf("first"),
            ),
            Arguments.of("top-level step selects its name", "library 'second@main'", listOf("second")),
            Arguments.of("missing name does not fall back to every entry", "library 'missing'", emptyList<String>()),
            Arguments.of(
                "nested step retains the empty-reference fallback",
                "node { library 'first' }",
                listOf("first", "second"),
            ),
            Arguments.of(
                "directive retains the empty-reference fallback",
                "pipeline { libraries { lib('first@main') } }",
                listOf("first", "second"),
            ),
        )
    }
}
