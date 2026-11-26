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
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextHandlerWorkerTest {
    enum class Route { TYPE_DEFINITION, INLAY_HINT }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `retirement after real parse cannot return the old answer`(route: Route) {
        Fixture(route).use { fixture ->
            val answer = fixture.request()
            fixture.awaitParse()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            val failure = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(failure.cause).responseError.code,
            )
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `cancellation during worker IO is not an empty answer`(route: Route) {
        Fixture(route).use { fixture ->
            val answer = fixture.request()
            fixture.awaitParse()
            assertTrue(answer.cancel(true))
            fixture.release.countDown()
            assertThrows<CancellationException> { answer.get(10, TimeUnit.SECONDS) }
        }
    }

    private class Fixture(private val route: Route) : AutoCloseable {
        private val uri = URI.create("file:///WorkerContext.groovy")
        private val source = "class Foo {}\nFoo f = new Foo(); println f\n"
        private val documents = DocumentProvider()
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
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

        fun request(): CompletableFuture<*> {
            val document = TextDocumentIdentifier(uri.toString())
            return when (route) {
                Route.TYPE_DEFINITION -> service.typeDefinition(TypeDefinitionParams(document, Position(1, 25)))
                Route.INLAY_HINT -> service.inlayHint(InlayHintParams(document, Range(Position(0, 0), Position(2, 0))))
            }
        }

        fun awaitParse() = assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not enter the real worker")

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
