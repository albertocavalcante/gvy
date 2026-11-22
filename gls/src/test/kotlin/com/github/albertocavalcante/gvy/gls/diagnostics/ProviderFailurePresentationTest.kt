// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderFailurePresentationTest {
    @Test
    fun `presentation groups each provider and bounds multiline failure details`() {
        val config = Diagnostic(
            Range(Position(0, 0), Position(0, 0)),
            "Bad ruleset " + "x".repeat(1000),
            DiagnosticSeverity.Information,
            "gvy",
            "gvy.providerFailure",
        ).apply { data = mapOf("provider" to "codenarc", "configurationUri" to "file:///rules.groovy") }
        val failures = listOf(
            DiagnosticProviderFailure("codenarc", "Failure", "Analyzer failed\nsecret stack"),
            DiagnosticProviderFailure("other", "Failure", "Other failure"),
        )
        val notices = consolidateProviderFailures(listOf(config), failures)
        assertEquals(2, notices.size)
        assertTrue(notices.first().message.contains("Analyzer failed"))
        assertTrue(notices.first().message.length <= 450)
        assertTrue(!notices.first().message.contains('\n'))
        assertEquals(listOf("codenarc", "other"), providerFailureStatus(notices).map { it.provider })
        assertEquals("file:///rules.groovy", providerFailureStatus(notices).first().configurationUri)
    }
}
