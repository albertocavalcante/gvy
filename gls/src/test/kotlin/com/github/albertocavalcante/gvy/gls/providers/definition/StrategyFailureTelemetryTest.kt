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
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A resolution strategy that throws is a failed lookup, not "no definition": the request reports one event
 * that keeps the error kind, and the telemetry sink and the collector keep their own exceptions.
 */
class StrategyFailureTelemetryTest {
    private val uri = URI.create("file:///Strategy.groovy")
    private val compilation = spyk(GroovyCompilationService())
    private val events = mutableListOf<DefinitionTelemetryEvent>()
    private val provider = DefinitionProvider(compilation, telemetrySink = DefinitionTelemetrySink { events += it })

    // An import of an unknown class reaches the classpath strategy, the last one tried.
    private val onMissingType = Position(0, 22)
    private val missingTypeSource = "import com.example.Missing\n"

    @Test
    fun `a throwing strategy is one failed event with the error kind, not no definition`(): Unit = runBlocking {
        compilation.compile(uri, missingTypeSource)
        every { compilation.findClasspathClass(any()) } throws IOException("jar unreadable")

        val definitions = provider.provideDefinitions(uri.toString(), onMissingType).toList()

        assertTrue(definitions.isEmpty())
        val event = events.single()
        assertEquals(DefinitionStatus.RESOLUTION_FAILED, event.status)
        assertTrue("IOException" in event.reason.orEmpty(), "reason: ${event.reason}")
        assertTrue("jar unreadable" in event.reason.orEmpty(), "reason: ${event.reason}")
    }

    @Test
    fun `a message-less failure names its kind, never null`(): Unit = runBlocking {
        compilation.compile(uri, missingTypeSource)
        every { compilation.findClasspathClass(any()) } throws IOException()

        provider.provideDefinitions(uri.toString(), onMissingType).toList()

        val reason = events.single().reason.orEmpty()
        assertTrue("IOException" in reason && "null" !in reason, "reason: $reason")
    }

    @Test
    fun `a long failure message is capped`(): Unit = runBlocking {
        compilation.compile(uri, missingTypeSource)
        every { compilation.findClasspathClass(any()) } throws IOException("x".repeat(5_000))

        provider.provideDefinitions(uri.toString(), onMissingType).toList()

        assertEquals(200, events.single().reason.orEmpty().length)
    }

    @Test
    fun `a throwing telemetry sink is not swallowed and reports once`(): Unit = runBlocking {
        compilation.compile(uri, "def value = 1\nprintln value\n")
        var calls = 0
        val failing = DefinitionProvider(
            compilation,
            telemetrySink = DefinitionTelemetrySink {
                calls++
                error("sink broke")
            },
        )

        val failure = assertFailsWith<IllegalStateException> {
            failing.provideDefinitions(uri.toString(), Position(1, 8)).toList()
        }

        assertEquals("sink broke", failure.message)
        assertEquals(1, calls, "a failing sink must not be reported to itself as a definition error")
    }

    @Test
    fun `a throwing collector does not add a second event`(): Unit = runBlocking {
        compilation.compile(uri, "def value = 1\nprintln value\n")

        val failure = assertFailsWith<IllegalStateException> {
            provider.provideDefinitions(uri.toString(), Position(1, 8)).collect { error("collector broke") }
        }

        assertEquals("collector broke", failure.message)
        assertEquals(DefinitionStatus.SUCCESS, events.single().status)
    }
}
