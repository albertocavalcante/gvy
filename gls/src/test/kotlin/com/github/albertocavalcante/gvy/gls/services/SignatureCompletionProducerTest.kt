// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class SignatureCompletionProducerTest {
    @Test
    fun `completion cannot return an old worker result after worker replacement`() {
        Fixture(Route.COMPLETION).use { fixture ->
            val answer = fixture.request()
            fixture.awaitParsed()
            assertTrue(fixture.compilation.updateSelectedWorker(worker("replacement")))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `signature cannot return an old worker result after worker replacement`() {
        Fixture(Route.SIGNATURE).use { fixture ->
            val answer = fixture.request()
            fixture.awaitParsed()
            assertTrue(fixture.compilation.updateSelectedWorker(worker("replacement")))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `empty signature cannot cross a classpath generation replacement`() {
        Fixture(Route.EMPTY_SIGNATURE).use { fixture ->
            fixture.compilation.classpathService.acquireClasspath().use { oldClasspath ->
                val answer = fixture.request()
                fixture.awaitParsed()
                assertTrue(oldClasspath.isCurrent(), "the held query must start in the observed generation")
                fixture.compilation.classpathService.updateClasspath(emptyList())
                assertTrue(!oldClasspath.isCurrent(), "the replacement must actually retire the generation")
                fixture.release.countDown()
                assertModified(answer)
            }
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val failure = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(failure.cause).responseError.code,
        )
    }

    private enum class Route { COMPLETION, SIGNATURE, EMPTY_SIGNATURE }

    private class Fixture(private val route: Route) : AutoCloseable {
        val uri = URI.create("file:///ProducerFence.groovy")
        val source = when (route) {
            Route.COMPLETION -> "class Greeter { String greet(String name) { name } }\nnew Greeter().gr"
            Route.SIGNATURE -> "class Greeter { String greet(String name) { name }; void run() { greet('x') } }"
            Route.EMPTY_SIGNATURE -> "class Empty {}\n"
        }
        private val position = when (route) {
            Route.COMPLETION -> Position(1, "new Greeter().gr".length)
            Route.SIGNATURE -> Position(0, source.indexOf("greet('x')") + "greet(".length)
            Route.EMPTY_SIGNATURE -> Position(0, 6)
        }
        private val documents = DocumentProvider()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val requests = CopyOnWriteArrayList<ParseRequest>()
        private val results = CopyOnWriteArrayList<ParseResult>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        requests.add(request)
                        results.add(parsed)
                        if (route == Route.COMPLETION) hold()
                        val model = parsed.astModel
                        return parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    if (route != Route.COMPLETION) hold()
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
            assertTrue(compilation.updateSelectedWorker(worker("initial")))
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
        }

        fun request(): CompletableFuture<*> {
            val document = TextDocumentIdentifier(uri.toString())
            return when (route) {
                Route.COMPLETION -> service.completion(CompletionParams(document, position))
                Route.SIGNATURE, Route.EMPTY_SIGNATURE -> service.signatureHelp(SignatureHelpParams(document, position))
            }
        }

        fun awaitParsed() {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach its real parse/query barrier")
            val request = requests.single()
            assertEquals(uri, request.uri)
            assertEquals(Phases.CANONICALIZATION, request.compilePhase)
            if (route == Route.COMPLETION) {
                assertTrue(request.content.contains("new Greeter().gr"))
            } else {
                assertEquals(source, request.content)
            }
            assertNotNull(results.single().ast, "the held producer must supply an AST")
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

    private companion object {
        fun worker(id: String) = WorkerDescriptor(
            id = id,
            supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("5.0.0")),
            capabilities = WorkerCapabilities(),
            connector = WorkerConnector.InProcess,
        )
    }
}
