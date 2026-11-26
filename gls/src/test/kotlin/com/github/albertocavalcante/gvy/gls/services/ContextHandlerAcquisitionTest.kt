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
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class ContextHandlerAcquisitionTest {
    enum class Route { TYPE_DEFINITION, INLAY_HINT }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `retirement during actual query rejects even an empty answer`(route: Route) {
        Fixture(route, "// no symbols\n").use { fixture ->
            val answer = fixture.request()
            fixture.awaitQuery()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `equal text reopen during actual query retires its answer`(route: Route) {
        Fixture(route, "class Foo {}\nFoo f = new Foo(); println f\n").use { fixture ->
            val answer = fixture.request()
            fixture.awaitQuery()
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.compilation.onDocumentOpened(fixture.uri)
            fixture.documents.put(fixture.uri, fixture.source, DocumentVersion.of(7))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val failure = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(failure.cause).responseError.code,
        )
    }

    private class Fixture(private val route: Route, val source: String) : AutoCloseable {
        val uri: URI = URI.create("file:///ContextHandler.groovy")
        val documents = DocumentProvider()
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val armed = AtomicBoolean(false)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        val model = parsed.astModel
                        return parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    if (route == Route.TYPE_DEFINITION) hold()
                                    return model.getNodeAt(uri, position)
                                }

                                override fun getAllNodes(): List<ASTNode> {
                                    if (route == Route.INLAY_HINT) hold()
                                    return model.getAllNodes()
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
            runBlocking { compilation.compile(uri, source) }
            armed.set(true)
        }

        fun request(): CompletableFuture<*> {
            val document = TextDocumentIdentifier(uri.toString())
            return when (route) {
                Route.TYPE_DEFINITION -> service.typeDefinition(TypeDefinitionParams(document, Position(0, 0)))
                Route.INLAY_HINT -> service.inlayHint(InlayHintParams(document, Range(Position(0, 0), Position(2, 0))))
            }
        }

        fun awaitQuery() = assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not enter its AST query")

        private fun hold() {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
