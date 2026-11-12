// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import com.github.albertocavalcante.gvy.diagnostics.codenarc.CodeNarcDiagnosticProvider
import com.github.albertocavalcante.gvy.diagnostics.codenarc.RulesetConfiguration
import com.github.albertocavalcante.gvy.diagnostics.codenarc.RulesetResolver
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.DiagnosticProviderAdapter
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CodeNarcCollectionFailureTest {
    private val uri = URI.create("file:///workspace/Example.groovy")
    private val healthyDiagnostic = Diagnostic(Range(Position(0, 0), Position(0, 1)), "healthy peer")

    @Test
    fun `real CodeNarc failures reach the collector while the list facade preserves healthy peers`(): Unit =
        runBlocking {
            val service = serviceWithCodeNarcFailure(IOException("ruleset unreadable"))

            val result = service.collectDiagnostics(uri, SOURCE)

            assertEquals(listOf("codenarc", "healthy"), result.providerIds)
            assertEquals(listOf(healthyDiagnostic), result.diagnostics)
            assertFalse(result.isSuccessful)
            val failure = result.failures.single()
            assertEquals("codenarc", failure.providerId)
            assertEquals(IOException::class.java.name, failure.exceptionType)
            assertEquals("ruleset unreadable", failure.message)
            assertEquals(listOf(healthyDiagnostic), service.getDiagnostics(uri, SOURCE))
        }

    @Test
    fun `real CodeNarc cancellation reaches the collector instead of returning empty success`(): Unit = runBlocking {
        val service = serviceWithCodeNarcFailure(CancellationException("ruleset cancelled"))

        val failure = assertFailsWith<CancellationException> { service.collectDiagnostics(uri, SOURCE) }

        assertEquals("ruleset cancelled", failure.message)
    }

    private fun serviceWithCodeNarcFailure(failure: Exception): DiagnosticsService {
        val workspace = object : WorkspaceContext {
            override val root: Path? = null
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }
        val resolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext): RulesetConfiguration = throw failure
        }
        val provider = CodeNarcDiagnosticProvider(workspace, rulesetResolver = resolver)
        val healthy = object : StreamingDiagnosticProvider {
            override val id: String = "healthy"
            override suspend fun provideDiagnostics(uri: URI, content: String) = flowOf(healthyDiagnostic)
        }
        return DiagnosticsService(listOf(DiagnosticProviderAdapter(provider, "codenarc"), healthy))
    }

    private companion object {
        const val SOURCE = "class Example {}"
    }
}
