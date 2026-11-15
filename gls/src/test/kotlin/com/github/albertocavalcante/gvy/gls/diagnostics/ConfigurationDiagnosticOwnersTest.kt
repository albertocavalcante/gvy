// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationDiagnosticOwnersTest {
    private val config = URI.create("file:///workspace/.codenarc")
    private val one = URI.create("file:///workspace/One.groovy")
    private val two = URI.create("file:///workspace/Two.groovy")
    private val diagnostic = Diagnostic().apply {
        range = Range(Position(0, 18), Position(0, 38))
        message = "Unknown ruleset"
        code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("gvy.providerFailure")
    }

    @Test
    fun `shared configuration notice remains until its last owner clears`() {
        val owners = ConfigurationDiagnosticOwners()
        owners.replace(one, mapOf(config to listOf(diagnostic)))
        owners.replace(two, mapOf(config to listOf(diagnostic)))
        assertEquals(listOf(diagnostic), owners.forUri(config))
        owners.remove(one)
        assertEquals(listOf(diagnostic), owners.forUri(config))
        owners.remove(two)
        assertTrue(owners.forUri(config).isEmpty())
    }

    @Test
    fun `clearing a changed configuration preserves unrelated owners`() {
        val other = URI.create("file:///workspace/other.groovy")
        val owners = ConfigurationDiagnosticOwners()
        owners.replace(one, mapOf(config to listOf(diagnostic), other to listOf(diagnostic)))
        owners.clearTarget(config)
        assertTrue(owners.forUri(config).isEmpty())
        assertEquals(listOf(diagnostic), owners.forUri(other))
    }
}
