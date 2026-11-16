// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeNarcPropertiesFileTest {
    @TempDir
    lateinit var dir: Path

    private fun write(text: String): Path = Files.writeString(dir.resolve("codenarc.properties"), text)

    @Test
    fun `a workspace properties file is applied to the analysis`() {
        val file = write("TrailingWhitespace.priority=1\n")

        val results = DefaultCodeAnalyzer().analyze(
            "def answer = 42   \n",
            "Answer.groovy",
            "ruleset { TrailingWhitespace }",
            file.toString(),
        )

        assertEquals(1, results.getNumberOfViolationsWithPriority(1, true))
    }

    @Test
    fun `regex properties wildcards unknown rules and mistyped values are dropped`() {
        val file = write(
            """
            MethodName.regex=(a+)+${'$'}
            ClassName.doNotApplyToFilesMatching=(a+)+
            ClassName.doNotApplyToClassNames=${"*".repeat(150)}x
            ClassName.applyToClassNames=Foo*
            NoSuchRule.priority=1
            TrailingWhitespace.nosuch=true
            TrailingWhitespace.priority=999999999
            ClassName.priority=-5
            LineLength.enabled=1e9
            TrailingWhitespace.enabled=true
            Evil.name.x=1
            """.trimIndent(),
        )

        val result = assertNotNull(PropertiesSanitizer.sanitize(file))

        assertEquals("TrailingWhitespace.enabled=true\n", result.content)
        assertTrue(
            result.rejectedKeys.containsAll(
                listOf("TrailingWhitespace.priority", "ClassName.priority", "LineLength.enabled"),
            ),
        )
    }

    @Test
    fun `plain name lists for string properties are kept`() {
        val file = write("ClassName.doNotApplyToClassNames=Foo,Bar\n")

        val result = assertNotNull(PropertiesSanitizer.sanitize(file))

        assertEquals("ClassName.doNotApplyToClassNames=Foo,Bar\n", result.content)
    }

    @Test
    fun `a symlinked properties file is not read`() {
        val target = write("TrailingWhitespace.priority=1\n")
        val link = Files.createSymbolicLink(dir.resolve("link.properties"), target)

        assertNull(PropertiesSanitizer.sanitize(link))
    }

    @Test
    fun `an oversized properties file is ignored`() {
        val file = write("a".repeat((PropertiesSanitizer.MAX_FILE_BYTES + 1).toInt()))

        assertNull(PropertiesSanitizer.sanitize(file))
    }

    @Test
    fun `a missing properties file does not break analysis`() {
        val results = DefaultCodeAnalyzer().analyze(
            "def answer = 42   \n",
            "Answer.groovy",
            "ruleset { TrailingWhitespace }",
            dir.resolve("absent.properties").toString(),
        )

        assertEquals(1, results.getTotalNumberOfFiles(true))
    }
}
