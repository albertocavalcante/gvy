// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A failed definition lookup reports one telemetry event that keeps the failure's kind and message, never a
 * failure followed by "no definition" or an unexpected exception rewritten as an unknown symbol.
 */
class DefinitionFailureTelemetryTest {
    private val uri = URI.create("file:///Failing.groovy")
    private val compilation = spyk(GroovyCompilationService())
    private val events = mutableListOf<DefinitionTelemetryEvent>()
    private val provider = DefinitionProvider(compilation, telemetrySink = DefinitionTelemetrySink { events += it })

    @Test
    fun `an unexpected failure is reported as an error with its own message`(): Unit = runBlocking {
        compilation.compile(uri, "def value = 1\nprintln value\n")
        every { compilation.getAst(uri) } throws IllegalStateException("ast unavailable")

        val definitions = provider.provideDefinitions(uri.toString(), Position(1, 8)).toList()

        assertTrue(definitions.isEmpty())
        val event = events.single()
        assertEquals(DefinitionStatus.ERROR, event.status)
        assertTrue("ast unavailable" in event.reason.orEmpty(), "reason: ${event.reason}")
        assertTrue("unknown" !in event.reason.orEmpty(), "reason: ${event.reason}")
    }

    @Test
    fun `a position that names no symbol is one no-definition event, not a failure`(): Unit = runBlocking {
        compilation.compile(uri, "println \"literal\"\n")

        val definitions = provider.provideDefinitions(uri.toString(), Position(0, 10)).toList()

        assertTrue(definitions.isEmpty())
        val event = events.single()
        assertEquals(DefinitionStatus.NO_DEFINITION, event.status)
    }
}
