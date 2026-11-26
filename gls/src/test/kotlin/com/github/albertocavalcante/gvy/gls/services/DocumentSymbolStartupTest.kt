// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
                        val retired = holdFirstParse.compareAndSet(true, false)
                        // Distinct old-worker output makes stale-result adoption observable.
                        val input = if (retired) {
                            request.copy(content = request.content.replace("AfterGdsl", "OldWorker"))
                        } else {
                            request
                        }
                        val parsed = delegate.parse(input)
                        if (retired) {
                            parsing.countDown()
                            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "Parse was not released" }
                        }
                        return parsed
                    }
                }
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = textService(scope, compilation, documents)
        service.updateHierarchicalDocumentSymbolSupport(true)
        try {
            compilation.onDocumentOpened(uri)
            documents.put(uri, "class AfterGdsl { String hello() { 'hello' } }", DocumentVersion.of(1))
            val response = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))
            assertTrue(parsing.await(WAIT_SECONDS, TimeUnit.SECONDS), "Symbol request did not begin parsing")

            // Startup selects the worker asynchronously, after didOpen and requests are already accepted.
            assertTrue(compilation.updateSelectedWorker(defaultWorkerDescriptors().first()))
            release.countDown()

            val symbols = response.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(symbols.any { it.isRight && it.right.name == "AfterGdsl" })
            assertTrue(symbols.any { it.isRight && it.right.children.orEmpty().any { child -> child.name == "hello" } })
            assertFalse(symbols.any { it.isRight && it.right.name == "OldWorker" })
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `worker replacement and cache clearing cannot return the old producer`() {
        val uri = URI.create("untitled:WorkerAndClear")
        val documents = DocumentProvider()
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val retired = count.incrementAndGet() == 1
                        val input = if (retired) request.copy(content = "class Retired {}") else request
                        val parsed = delegate.parse(input)
                        if (retired) {
                            parsing.countDown()
                            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        }
                        return parsed
                    }
                }
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = textService(scope, compilation, documents)
        try {
            compilation.onDocumentOpened(uri)
            documents.put(uri, "class Current {}", DocumentVersion.of(1))
            val answer = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))
            assertTrue(parsing.await(WAIT_SECONDS, TimeUnit.SECONDS))
            RequestDispatchGate(executor).use { gate ->
                assertTrue(gate.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                assertTrue(compilation.updateSelectedWorker(defaultWorkerDescriptors().first()))
                compilation.clearCaches()
            }
            release.countDown()
            try {
                val symbols = answer.get(WAIT_SECONDS, TimeUnit.SECONDS)
                assertTrue(count.get() >= 2, "an answer requires a new producer")
                assertTrue(symbols.any { it.isLeft && it.left.name == "Current" })
                assertFalse(symbols.any { it.isLeft && it.left.name == "Retired" })
            } catch (error: ExecutionException) {
                assertEquals(
                    ResponseErrorCode.ContentModified.value,
                    assertIs<ResponseErrorException>(error.cause).responseError.code,
                )
            }
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
            compilation.shutdown()
        }
    }

    @Test
    fun `symbols describe the current buffer when an older symbol index exists`(): Unit = runBlocking {
        val uri = URI.create("untitled:Untitled-1")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = textService(scope, compilation, documents)
        service.updateHierarchicalDocumentSymbolSupport(true)
        try {
            compilation.onDocumentOpened(uri)
            documents.put(uri, "class Before {}", DocumentVersion.of(1))
            compilation.ensureCompiled(uri)
            documents.update(uri, "class After {}", DocumentVersion.of(2))

            val response = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))
            val symbols = response.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(symbols.any { it.isRight && it.right.name == "After" })
            assertFalse(symbols.any { it.isRight && it.right.name == "Before" })
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun textService(
        scope: CoroutineScope,
        compilation: GroovyCompilationService,
        documents: DocumentProvider,
    ): GroovyTextDocumentService = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    private class RequestDispatchGate(executor: ExecutorService) : AutoCloseable {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        init {
            executor.execute {
                entered.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS))
            }
        }

        override fun close() {
            release.countDown()
        }
    }

    companion object {
        private const val WAIT_SECONDS = 10L
    }
}
