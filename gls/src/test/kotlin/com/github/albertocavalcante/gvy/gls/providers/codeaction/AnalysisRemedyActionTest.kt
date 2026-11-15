// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnalysisRemedyActionTest {
    @Test
    fun `source model limitations offer dependency refresh for the requested file`() {
        val diagnostic = Diagnostic().apply {
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("gvy.sourceModel")
        }
        val actions = AnalysisRemedyAction.create("file:///A.groovy", listOf(diagnostic), null)
        assertEquals(listOf("Show analysis details", "Refresh Dependencies"), actions.map { it.title })
        assertEquals(listOf("file:///A.groovy"), actions.first().command.arguments)
    }

    @Test
    fun `offers only analysis remedies for matching diagnostics and kind`() {
        val diagnostic = Diagnostic().apply { code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("gvy.degraded") }
        val actions = AnalysisRemedyAction.create(
            "file:///A.groovy",
            listOf(diagnostic),
            listOf(CodeActionKind.QuickFix),
        )
        assertEquals(listOf("Show analysis details", "Refresh Dependencies"), actions.map { it.title })
        assertEquals(
            emptyList<Any>(),
            AnalysisRemedyAction.create("file:///A.groovy", listOf(diagnostic), listOf(CodeActionKind.SourceFixAll)),
        )
        assertEquals(emptyList<Any>(), AnalysisRemedyAction.create("file:///A.groovy", listOf(Diagnostic()), null))
    }

    @Test
    fun `provider failures offer configuration when supplied`() {
        val diagnostic = Diagnostic().apply {
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("gvy.providerFailure")
            data = mapOf("configurationUri" to "file:///project/.codenarc")
        }
        val actions = AnalysisRemedyAction.create("file:///A.groovy", listOf(diagnostic), null)
        assertEquals(listOf("Show analysis details", "Open configuration"), actions.map { it.title })
        assertEquals(listOf("file:///project/.codenarc"), actions.last().command.arguments)
    }
}
