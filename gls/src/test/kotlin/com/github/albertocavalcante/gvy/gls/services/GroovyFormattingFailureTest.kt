// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.fmt.FormatOutcome
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GroovyFormattingFailureTest {
    private val uri = URI.create("file:///formatting-failure.groovy")
    private val telemetry = mutableListOf<Any>()
    private val client = mockk<LanguageClient>().also {
        every { it.telemetryEvent(capture(telemetry)) } just Runs
    }

    @Test
    fun `checked formatter cancellation propagates without telemetry`(): Unit = runBlocking {
        val failure = CancellationException("formatting cancelled")

        assertSame(failure, assertFailsWith<CancellationException> { service(failure).format(params()) })

        assertTrue(telemetry.isEmpty())
    }

    @Test
    fun `checked formatter fatal failure propagates without telemetry`(): Unit = runBlocking {
        val failure = LinkageError("formatter linkage failed")

        assertSame(failure, assertFailsWith<LinkageError> { service(failure).format(params()) })

        assertTrue(telemetry.isEmpty())
    }

    @Test
    fun `ordinary checked formatter failure retains error telemetry and empty edits`(): Unit = runBlocking {
        assertTrue(service(IOException("formatter unavailable")).format(params()).isEmpty())

        assertErrorTelemetry("formatter unavailable")
    }

    @Test
    fun `ordinary checked formatter failure without a message retains its type in telemetry`(): Unit = runBlocking {
        assertTrue(service(IOException()).format(params()).isEmpty())

        assertErrorTelemetry("IOException")
    }

    private fun service(failure: Throwable): GroovyFormattingService {
        val formatter = object : Formatter {
            override fun format(text: String): String = error("The service must use the checked formatter")
            override fun formatChecked(text: String): FormatOutcome = throw failure
        }
        return GroovyFormattingService(
            formatter,
            DocumentProvider().apply { put(uri, "def x = 1") },
            { client },
        )
    }

    private fun params() = DocumentFormattingParams(TextDocumentIdentifier(uri.toString()), FormattingOptions(4, true))

    private fun assertErrorTelemetry(message: String) {
        val event = telemetry.single() as FormatterTelemetryEvent
        assertEquals(uri.toString(), event.uri)
        assertEquals(FormatterStatus.ERROR, event.status)
        assertEquals(message, event.errorMessage)
        assertEquals(false, event.ignoredOptions)
        assertTrue(event.durationMs >= 0)
    }
}
