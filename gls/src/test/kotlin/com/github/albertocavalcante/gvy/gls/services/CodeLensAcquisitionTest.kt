// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.testing.api.TestFramework
import com.github.albertocavalcante.gvy.testing.api.TestFrameworkDetector
import com.github.albertocavalcante.gvy.testing.api.TestItem
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.CodeLensParams
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

class CodeLensAcquisitionTest {
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
    fun `registry changes during discovery reject the captured result`() {
        val registry = TestFrameworkRegistry.default
        val previous = registry.getDetectors()
        registry.clear()
        try {
            Fixture(computing = true).use { fixture ->
                registry.register(fixture.detector)
                val answer = fixture.request()
                fixture.awaitBarrier()
                registry.register(fixture.detector)
                fixture.release.countDown()
                assertModified(answer)
            }
        } finally {
            registry.clear()
            previous.forEach(registry::register)
        }
    }

    @Test
    fun `retirement during empty discovery rejects the answer`() {
        val registry = TestFrameworkRegistry.default
        val previous = registry.getDetectors()
        registry.clear()
        try {
            Fixture(computing = true).use { fixture ->
                registry.register(fixture.detector)
                val answer = fixture.request()
                fixture.awaitBarrier()
                fixture.compilation.invalidateCache(URI.create("file:///Unseen.groovy"))
                fixture.release.countDown()
                assertModified(answer)
            }
        } finally {
            registry.clear()
            previous.forEach(registry::register)
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    private class Fixture(private val computing: Boolean = false) : AutoCloseable {
        val uri = URI.create("file:///Acquisition.groovy")
        val source = if (computing) {
            "class First {}\nclass Second {}\n"
        } else {
            "class Sample { static void main(String[] args) {} }\n"
        }
        val documents = DocumentProvider()
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        val detector = object : TestFrameworkDetector {
            override val framework = TestFramework.SPOCK

            override fun appliesTo(classNode: ClassNode, module: ModuleNode?, classLoader: ClassLoader?): Boolean {
                if (computing && first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return false
            }

            override fun extractTests(classNode: ClassNode): List<TestItem> = emptyList()
        }
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
                        return parsed
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

        fun request() = service.codeLens(CodeLensParams(TextDocumentIdentifier(uri.toString())))

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
