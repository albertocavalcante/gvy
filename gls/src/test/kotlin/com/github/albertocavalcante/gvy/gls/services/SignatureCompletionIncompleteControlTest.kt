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
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignatureCompletionIncompleteControlTest {
    @Test
    fun `cold incomplete signature request retains useful current help`(): Unit = runBlocking {
        val source = "class Example {\n    def run() {\n        println("
        Fixture(source).use { fixture ->
            val help = fixture.service.signatureHelp(
                SignatureHelpParams(fixture.document, Position(2, "        println(".length)),
            ).await()
            assertTrue(fixture.requests.any { it.content == source && it.compilePhase == Phases.CANONICALIZATION })
            assertTrue(fixture.requests.any { it.content != source }, "incomplete call needs its recovery copy")
            assertTrue(fixture.results.any { it.ast != null }, "recovery must produce an AST")
            assertTrue(help.signatures.any { it.label.startsWith("void println") }, "found ${help.signatures}")
            assertEquals(0, help.activeParameter)
        }
    }

    @Test
    fun `cold incomplete completion request retains current local member`(): Unit = runBlocking {
        val source = "class Greeter { String greet(String name) { name } }\nnew Greeter().gr"
        Fixture(source).use { fixture ->
            val answer = fixture.service.completion(
                CompletionParams(fixture.document, Position(1, "new Greeter().gr".length)),
            ).await()
            val request = fixture.requests.first()
            assertEquals(Phases.CANONICALIZATION, request.compilePhase)
            assertTrue(request.content.contains("new Greeter().gr"))
            assertNotNull(fixture.results.first().ast, "dummy-inserted current source must produce an AST")
            val items = if (answer.isLeft) answer.left else answer.right.items
            assertTrue(items.any { it.label == "greet" }, "found ${items.map { it.label }}")
        }
    }

    private class Fixture(source: String) : AutoCloseable {
        val uri = URI.create("file:///IncompleteControl.groovy")
        val document = TextDocumentIdentifier(uri.toString())
        val requests = CopyOnWriteArrayList<ParseRequest>()
        val results = CopyOnWriteArrayList<ParseResult>()
        private val documents = DocumentProvider()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        requests.add(request)
                        results.add(parsed)
                        return parsed
                    }
                }
            },
        )
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        init {
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
        }

        override fun close() {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
