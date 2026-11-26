// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextHandlerColdTest {
    @Test
    fun `cold type definition parses current buffer and returns its declaration`() {
        val uri = URI.create("file:///ColdType.groovy")
        val source = "class Foo {}\nFoo f = new Foo(); println f\n"
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(7)) }
        val parses = AtomicInteger()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        parses.incrementAndGet()
                        return delegate.parse(request)
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.onDocumentOpened(uri)
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val locations = service.typeDefinition(
                TypeDefinitionParams(TextDocumentIdentifier(uri.toString()), Position(1, 27)),
            ).get(10, TimeUnit.SECONDS).left
            assertTrue(parses.get() > 0, "the cold request must acquire a real current parse")
            assertEquals(1, locations.size)
            assertEquals(uri.toString(), locations.single().uri)
            assertEquals(Range(Position(0, 0), Position(0, 12)), locations.single().range)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
