// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "workspace ruleset is ignored" notice belongs on the ignored file only, not on every analysed file. */
class IgnoredWorkspaceRulesetNoticeTest {
    @TempDir
    lateinit var tempDir: Path

    private fun provider(root: Path) = CodeNarcDiagnosticProvider(
        object : WorkspaceContext {
            override val root: Path = root
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = true
            }
        },
    )

    @Test
    fun `the notice is attached to the ignored ruleset file only`() = runBlocking {
        val ruleset = tempDir.resolve("codenarc.groovy")
        Files.writeString(ruleset, "ruleset { evaluate 'println 1' }")
        val other = tempDir.resolve("Other.groovy")
        Files.writeString(other, "class Other {}\n")
        val provider = provider(tempDir)

        val onRuleset = provider.analyze("ruleset { evaluate 'println 1' }\n", ruleset.toUri())
        val onOther = provider.analyze("class Other {}\n", other.toUri())

        val notices = onRuleset.filter { it.severity == DiagnosticSeverity.Information && "ignored" in it.message }
        assertEquals(1, notices.size, "expected one notice on the ruleset file, got ${onRuleset.map { it.message }}")
        assertTrue(
            onOther.none { it.severity == DiagnosticSeverity.Information && "ignored" in it.message },
            "other files must not carry the notice, got ${onOther.map { it.message }}",
        )
    }

    @Test
    fun `the user is told once per ignored ruleset file`() = runBlocking {
        Files.writeString(tempDir.resolve("codenarc.groovy"), "ruleset { evaluate 'println 1' }")
        val other = tempDir.resolve("Other.groovy")
        Files.writeString(other, "class Other {}\n")
        val told = mutableListOf<String>()
        val provider = CodeNarcDiagnosticProvider(
            object : WorkspaceContext {
                override val root: Path = tempDir
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
            onWorkspaceRulesetIgnored = { told.add(it) },
        )

        provider.analyze("class Other {}\n", other.toUri())
        provider.analyze("class Other {}\n", other.toUri())

        assertEquals(listOf("codenarc.groovy"), told)
    }
}
