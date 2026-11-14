// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentSymbolStartupTest {
    @Test
    fun `first symbol request survives startup worker selection`() {
        val uri = URI.create("untitled:Untitled-1")
        val documents = DocumentProvider()
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdFirstParse = AtomicBoolean(true)
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        if (holdFirstParse.compareAndSet(true, false)) {
                            parsing.countDown()
                            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Parse was not released" }
                        }
                        return delegate.parse(request)
                    }
                }
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        try {
            compilation.onDocumentOpened(uri)
            documents.put(uri, "class AfterGdsl { String hello() { 'hello' } }", 1)
            val response = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))
            assertTrue(parsing.await(WAIT_SECONDS, TimeUnit.SECONDS), "Symbol request did not begin parsing")

            // Startup selects the worker asynchronously, after didOpen and requests are already accepted.
            assertTrue(compilation.updateSelectedWorker(defaultWorkerDescriptors().first()))
            release.countDown()

            val symbols = response.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(symbols.any { it.isRight && it.right.name == "AfterGdsl" })
            assertTrue(symbols.any { it.isRight && it.right.name == "hello" })
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `symbols describe the current buffer when an older symbol index exists`(): Unit = runBlocking {
        val uri = URI.create("untitled:Untitled-1")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        try {
            compilation.onDocumentOpened(uri)
            documents.put(uri, "class Before {}", 1)
            compilation.ensureCompiled(uri)
            documents.update(uri, "class After {}", 2)

            val response = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))
            val symbols = response.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(symbols.any { it.isRight && it.right.name == "After" })
            assertFalse(symbols.any { it.isRight && it.right.name == "Before" })
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    companion object {
        private const val WAIT_SECONDS = 10L
    }
}
