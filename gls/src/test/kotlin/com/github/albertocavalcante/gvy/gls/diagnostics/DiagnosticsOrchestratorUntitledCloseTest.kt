// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticsOrchestratorUntitledCloseTest {
    @Test
    fun `a retired close cannot remove a reopened untitled buffers real symbol index`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val uri = URI.create("untitled:Scratch")
            val documents = DocumentProvider()
            val compilation = GroovyCompilationService(
                ioDispatcher = Dispatchers.Unconfined,
                documentProvider = documents,
            )
            val client = mockk<LanguageClient>(relaxed = true)
            var reopenOnLookup = false
            lateinit var service: GroovyTextDocumentService
            service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(
                    documentProvider = documents,
                    serverConfiguration = parserOnlyConfiguration(),
                    client = {
                        if (reopenOnLookup) {
                            reopenOnLookup = false
                            service.didOpen(open(uri, "class Reopened {}"))
                            val fresh = assertNotNull(compilation.symbolIndexingService.getSymbolIndex(uri))
                            assertTrue("Reopened" in fresh.getSymbols(uri).map { it.name })
                        }
                        client
                    },
                ),
            )
            service.didOpen(open(uri, "class Original {}"))
            assertNotNull(compilation.symbolIndexingService.getSymbolIndex(uri))
            reopenOnLookup = true

            service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))

            assertEquals("class Reopened {}", documents.get(uri))
            val retained = assertNotNull(compilation.symbolIndexingService.getSymbolIndex(uri))
            assertTrue("Reopened" in retained.getSymbols(uri).map { it.name })
        } finally {
            scope.cancel()
        }
    }

    private fun parserOnlyConfiguration(): ServerConfiguration {
        val base = ServerConfiguration()
        return base.copy(
            diagnosticConfig = base.diagnosticConfig.copy(
                disabledProviders = setOf("codenarc", "custom-rules", "unused-imports"),
            ),
        )
    }

    private fun open(uri: URI, content: String) =
        DidOpenTextDocumentParams(TextDocumentItem(uri.toString(), "groovy", 1, content))
}
