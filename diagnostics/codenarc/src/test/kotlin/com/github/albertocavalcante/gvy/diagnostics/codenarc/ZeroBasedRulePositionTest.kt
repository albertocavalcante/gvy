// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class ZeroBasedRulePositionTest {
    @Test
    fun `consecutive blank lines are reported on the extra blank line`() {
        val diagnostics = provider().analyzeAndGetDiagnostics("def a = 1\n\n\ndef b = 2\n", "Example.groovy")
            .filter { it.code.left == "ConsecutiveBlankLines" }
        assertEquals(2, diagnostics.single().range.start.line)
    }

    @Test
    fun `blank first line before package is reported on line one`() {
        val diagnostics = provider().analyzeAndGetDiagnostics("\npackage foo\n\nclass A {}\n", "Example.groovy")
            .filter { it.code.left == "BlankLineBeforePackage" }
        assertEquals(0, diagnostics.single().range.start.line)
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
}
