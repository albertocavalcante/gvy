// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

class EditorFactAcquisitionTest {
    enum class Route { HIGHLIGHT, PREPARE_CALL }
    private enum class Stage { PARSED, QUERY }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `clear after parsing cannot produce an empty cache miss`(route: Route) {
        Fixture(route, Stage.PARSED).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            fixture.assertModifiedOrNewAnswer(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `retirement during computation rejects even a previously held parse`(route: Route) {
        Fixture(route, Stage.QUERY).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.invalidateCache(URI.create("file:///UnseenCompanion.groovy"))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `equal text and version reopened during computation cannot answer the old request`(route: Route) {
        Fixture(route, Stage.QUERY).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.compilation.onDocumentOpened(fixture.uri)
            fixture.documents.put(fixture.uri, fixture.source, DocumentVersion.of(7))
            fixture.release.countDown()
            assertModified(answer)
            fixture.assertFullAnswer(fixture.request().get(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `current open source returns exact editor facts`(route: Route) {
        Fixture(route, null).use { fixture ->
            fixture.assertFullAnswer(fixture.request().get(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `a degraded current parse retains unaffected local facts`(route: Route) {
        val source = when (route) {
            Route.HIGHLIGHT -> "def counter = 0\ncounter = counter + 1\nprintln counter\nMissingDependency missing\n"
            Route.PREPARE_CALL -> "def targetMethod() {}\ntargetMethod()\nMissingDependency missing\n"
        }
        Fixture(route, null, sourceOverride = source).use { fixture ->
            fixture.assertFullAnswer(fixture.request().get(10, TimeUnit.SECONDS))
            val parsed = fixture.results.single()
            assertTrue(
                parsed.outcome.isDegraded || !parsed.isSuccessful ||
                    parsed.outcome.achievedPhase < Phases.CANONICALIZATION,
                "the fixture must exercise a partial or degraded current parse",
            )
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `empty answer is retired during query`(route: Route) {
        Fixture(route, Stage.QUERY, sourceOverride = "// no symbols\n").use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `cancelling a request held in the worker stays cancelled`(route: Route) {
        Fixture(route, Stage.PARSED).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            assertTrue(answer.cancel(true))
            fixture.release.countDown()
            assertThrows<CancellationException> { answer.get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `open and cold closed source use their respective parse policies`(route: Route, @TempDir dir: Path) {
        Fixture(route, null).use { fixture ->
            fixture.assertFullAnswer(fixture.request().get(10, TimeUnit.SECONDS))
            assertEquals(ParseMode.WORKSPACE, fixture.requests.single().parseMode)
            assertEquals(Phases.CANONICALIZATION, fixture.requests.single().compilePhase)
        }
        val path = dir.resolve("Closed.groovy")
        val source = when (route) {
            Route.HIGHLIGHT -> "def counter = 0\ncounter = counter + 1\nprintln counter\n"
            Route.PREPARE_CALL -> "def targetMethod() {}\ntargetMethod()\n"
        }
        Files.writeString(path, source)
        Fixture(route, null, sourceOverride = source, fileUri = path.toUri(), open = false).use { fixture ->
            fixture.assertFullAnswer(fixture.request().get(10, TimeUnit.SECONDS))
            assertEquals(ParseMode.MINIMAL, fixture.requests.single().parseMode)
            assertEquals(Phases.CANONICALIZATION, fixture.requests.single().compilePhase)
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
        private val route: Route,
        private val stage: Stage?,
        sourceOverride: String? = null,
        fileUri: URI = URI.create("file:///EditorFact.groovy"),
        open: Boolean = true,
    ) : AutoCloseable {
        val uri = fileUri
        val source = sourceOverride ?: when (route) {
            Route.HIGHLIGHT -> "def counter = 0\ncounter = counter + 1\nprintln counter\n"
            Route.PREPARE_CALL -> "def targetMethod() {}\ntargetMethod()\n"
        }
        val documents = DocumentProvider()
        val compilation: GroovyCompilationService
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val parses = AtomicInteger()
        val requests = CopyOnWriteArrayList<ParseRequest>()
        val results = CopyOnWriteArrayList<ParseResult>()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val service: GroovyTextDocumentService

        init {
            compilation = GroovyCompilationService(
                documentProvider = documents,
                workerSessionDecorator = { delegate ->
                    object : WorkerSession {
                        override fun parse(request: ParseRequest): ParseResult {
                            parses.incrementAndGet()
                            requests.add(request)
                            val parsed = delegate.parse(request)
                            results.add(parsed)
                            if (stage == Stage.PARSED) hold()
                            val model = parsed.astModel
                            return parsed.copy(
                                astModel = object : GroovyAstModel by model {
                                    override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                        if (stage == Stage.QUERY) hold()
                                        return model.getNodeAt(uri, position)
                                    }
                                },
                            )
                        }
                    }
                },
            )
            service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            if (open) {
                compilation.onDocumentOpened(uri)
                documents.put(uri, source, DocumentVersion.of(7))
            }
        }

        fun request(): CompletableFuture<*> {
            val document = TextDocumentIdentifier(uri.toString())
            return when (route) {
                Route.HIGHLIGHT -> service.documentHighlight(DocumentHighlightParams(document, Position(0, 4)))
                Route.PREPARE_CALL -> service.prepareCallHierarchy(CallHierarchyPrepareParams(document, Position(0, 6)))
            }
        }

        fun awaitBarrier() = assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach $stage")

        private fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "request was not released at $stage" }
        }

        fun assertModifiedOrNewAnswer(answer: CompletableFuture<*>) {
            try {
                val result = answer.get(10, TimeUnit.SECONDS)
                assertTrue(parses.get() > 1, "a successful retry must use a new producer")
                assertFullAnswer(result)
            } catch (error: ExecutionException) {
                assertEquals(
                    ResponseErrorCode.ContentModified.value,
                    assertIs<ResponseErrorException>(error.cause).responseError.code,
                )
            }
        }

        fun assertFullAnswer(answer: Any?) {
            val values = assertIs<List<*>>(answer)
            when (route) {
                Route.HIGHLIGHT -> assertEquals(
                    setOf(
                        Range(Position(0, 4), Position(0, 11)),
                        Range(Position(1, 0), Position(1, 7)),
                        Range(Position(1, 10), Position(1, 17)),
                        Range(Position(2, 8), Position(2, 15)),
                    ),
                    values.map { assertIs<DocumentHighlight>(it).range }.toSet(),
                )
                Route.PREPARE_CALL -> {
                    val item = assertIs<CallHierarchyItem>(values.single())
                    assertEquals("targetMethod", item.name)
                    assertEquals(Range(Position(0, 4), Position(0, 16)), item.selectionRange)
                }
            }
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
