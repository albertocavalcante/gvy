// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LegacyJdkMethodNavigationTest {
    @TempDir lateinit var directory: Path

    @Test
    @EnabledIf("hasJdkSources")
    fun `legacy JDK method navigation selects the method declaration`() = runBlocking {
        val className = "java.util.HashMap"
        val classUri = URI.create("jrt:/java.base/java/util/HashMap.class")
        val jdkSources = JdkSourceResolver(directory.resolve("jdk-sources"))
        SourceNavigationService(
            sourceCacheRoot = directory.resolve("jdk-navigation"),
            jdkSourceResolver = jdkSources,
        ).use { navigation ->
            val classLocation = assertIs<SourceNavigator.SourceResult.SourceLocation>(
                navigation.navigateToSource(classUri, className),
            )
            val methodLocation = assertIs<SourceNavigator.SourceResult.SourceLocation>(
                navigation.navigateToMethodSource(classUri, className, "clear"),
            )
            val definition = assertNotNull(methodLocation.definition)
            val methodLineNumber = assertNotNull(methodLocation.lineNumber)
            val classLineNumber = assertNotNull(classLocation.lineNumber)
            val methodLine = Files.readString(Path.of(methodLocation.uri)).lines()[definition.nameRange.startLine]

            assertEquals(
                "clear",
                methodLine.substring(definition.nameRange.startCharacter, definition.nameRange.endCharacter),
            )
            assertEquals(definition.nameRange.startLine + 1, methodLineNumber)
            assertNotEquals(classLineNumber, methodLineNumber)
        }
    }

    @Test
    @EnabledIf("hasJdkSources")
    fun `legacy JDK method navigation leaves overloads and unknown methods unresolved`() = runBlocking {
        val className = "java.lang.String"
        val classUri = URI.create("jrt:/java.base/java/lang/String.class")
        val jdkSources = JdkSourceResolver(directory.resolve("jdk-sources"))
        SourceNavigationService(
            sourceCacheRoot = directory.resolve("jdk-navigation"),
            jdkSourceResolver = jdkSources,
        ).use { navigation ->
            for (methodName in listOf("indexOf", "missingMethod")) {
                val result = assertIs<SourceNavigator.SourceResult.SourceLocation>(
                    navigation.navigateToMethodSource(classUri, className, methodName),
                )

                assertEquals(className, result.className)
                assertNull(result.lineNumber, "$methodName must not inherit the class line")
                assertNull(result.documentation, "$methodName must not inherit class documentation")
                assertNull(result.definition, "$methodName must not be reported as the class declaration")
            }
        }
    }

    companion object {
        @JvmStatic
        fun hasJdkSources(): Boolean = JdkSourceResolver().findSrcZip() != null
    }
}
