// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentSymbolAcquisitionTest {
    @Test
    fun `cache retirement after parsing rejects the captured request`() {
        Fixture().use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `reopening the same text and version rejects the captured request`() {
        Fixture().use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.compilation.onDocumentOpened(fixture.uri)
            fixture.documents.put(fixture.uri, fixture.source, DocumentVersion.of(7))
            fixture.release.countDown()
            assertModified(answer)
            assertTrue(fixture.request().get(10, TimeUnit.SECONDS).isNotEmpty())
        }
    }

    @Test
    fun `cancelling a request remains cancellation`() {
        Fixture().use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            assertTrue(answer.cancel(true))
            fixture.release.countDown()
            assertTrue(answer.isCancelled)
        }
    }

    @Test
    fun `retirement during symbol construction rejects even an empty answer`() {
        Fixture(computing = true, source = "// no symbols\n").use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.invalidateCache(URI.create("file:///Unseen.groovy"))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    private class Fixture(
        private val computing: Boolean = false,
        val source: String = "class Sample { static void main(String[] args) {} }\n",
    ) : AutoCloseable {
        val uri = URI.create("file:///Acquisition.groovy")
        val documents = DocumentProvider()
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        if (!computing && first.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        val model = object : GroovyAstModel by parsed.astModel {
                            override fun getAllClassNodes(): List<ClassNode> {
                                if (computing && Thread.currentThread().stackTrace.any {
                                        it.className.contains("GroovyTextDocumentService\$documentSymbol")
                                    } && first.compareAndSet(true, false)
                                ) {
                                    entered.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                }
                                return parsed.astModel.getAllClassNodes()
                            }
                        }
                        return parsed.copy(astModel = model)
                    }
                }
            },
        )
        private val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        init {
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
        }

        fun request() = service.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri.toString())))

        fun awaitBarrier() {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach the worker barrier")
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
