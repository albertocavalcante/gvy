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
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class SignatureCompletionSnapshotTest {
    @Test
    fun `completion rejects a same text reopen after its transient producer`() {
        Fixture(Route.COMPLETION).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.reopen()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `signature query rejects retirement even for an empty result`() {
        Fixture(Route.SIGNATURE, "class Empty {}\n", Position(0, 6)).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `signature query rejects a same text reopen`() {
        Fixture(Route.SIGNATURE).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.reopen()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `cancelling completion at the transient producer stays cancelled`() {
        Fixture(Route.COMPLETION).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            assertTrue(answer.cancel(true))
            fixture.release.countDown()
            assertThrows<CancellationException> { answer.get(10, TimeUnit.SECONDS) }
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    private enum class Route { SIGNATURE, COMPLETION }

    private class Fixture(
        private val route: Route,
        val source: String = when (route) {
            Route.SIGNATURE -> "class Greeter { String greet(String name) { name }; void run() { greet('x') } }"
            Route.COMPLETION -> "class Greeter { String greet(String name) { name } }\nnew Greeter().gr"
        },
        private val position: Position = when (route) {
            Route.SIGNATURE -> Position(0, source.indexOf("greet('x')") + "greet(".length)
            Route.COMPLETION -> Position(1, "new Greeter().gr".length)
        },
    ) : AutoCloseable {
        val uri: URI = URI.create("file:///SnapshotRequest.groovy")
        val documents = DocumentProvider()
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        if (route == Route.COMPLETION) hold()
                        val model = parsed.astModel
                        return parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    if (route == Route.SIGNATURE) hold()
                                    return model.getNodeAt(uri, position)
                                }
                            },
                        )
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
                Route.SIGNATURE -> service.signatureHelp(SignatureHelpParams(document, position))
                Route.COMPLETION -> service.completion(CompletionParams(document, position))
            }
        }

        fun awaitBarrier() = assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach $route producer")

        fun reopen() {
            documents.remove(uri)
            compilation.onClosedSourceChanged(uri)
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
        }

        private fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "request was not released" }
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
