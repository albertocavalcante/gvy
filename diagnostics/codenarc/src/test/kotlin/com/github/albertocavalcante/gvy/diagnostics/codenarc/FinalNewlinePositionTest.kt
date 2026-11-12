// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinalNewlinePositionTest {
    @TestFactory
    fun `the real final newline rule reports the final source line`(): List<DynamicTest> = listOf(
        "def value = 1" to 0,
        "def first = 1\ndef second = 2" to 1,
        "def first = 1\r\ndef second = 2" to 1,
        "def first = 1\n\n// final line" to 2,
        "def value = 1\r" to 0,
        "def first = 1\ndef second = 2\r" to 1,
        "def value = 1\r\r" to 1,
    ).map { (source, lastLine) ->
        dynamicTest(source.replace("\n", "\\n").replace("\r", "\\r")) {
            val diagnostics = provider().analyzeAndGetDiagnostics(source, "Example.groovy")
                .filter { it.code.left == RULE }

            val diagnostic = diagnostics.single()
            val line = source.lines()[lastLine]
            assertEquals(lastLine, diagnostic.range.start.line)
            assertEquals(lastLine, diagnostic.range.end.line)
            assertTrue(diagnostic.range.start.character in 0..line.length)
            assertTrue(diagnostic.range.end.character in diagnostic.range.start.character..line.length)
        }
    }

    @TestFactory
    fun `empty or newline terminated input has no final newline violation`(): List<DynamicTest> = listOf(
        "",
        "def value = 1\n",
        "def value = 1\r\n",
    ).map { source ->
        dynamicTest(source.replace("\n", "\\n").replace("\r", "\\r").ifEmpty { "empty" }) {
            val diagnostics = provider().analyzeAndGetDiagnostics(source, "Example.groovy")

            assertTrue(diagnostics.none { it.code.left == RULE })
        }
    }

    private fun provider(): CodeNarcDiagnosticProvider {
        val workspace = object : WorkspaceContext {
            override val root: Path? = null
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = false
            }
        }
        val resolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext) =
                RulesetConfiguration("ruleset { ruleset('rulesets/formatting.xml') }")
        }
        return CodeNarcDiagnosticProvider(workspace, rulesetResolver = resolver)
    }

    private companion object {
        const val RULE = "FileEndsWithoutNewline"
    }
}
