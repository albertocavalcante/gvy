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
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class LocalRangeAcquisitionTest {
    enum class Route { FOLDING, SELECTION }

    private enum class Stage { PARSED, COMPUTING }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `mismatched document authorities cannot parse a different source`(route: Route) {
        Fixture(route, Stage.PARSED, sharedProvider = false).use { fixture ->
            assertEquals(fixture.documents.getSnapshot(fixture.uri), fixture.compilerDocuments.getSnapshot(fixture.uri))
            assertNotSame(
                fixture.documents.getSnapshot(fixture.uri),
                fixture.compilerDocuments.getSnapshot(fixture.uri),
            )
            assertModified(fixture.request())
            assertEquals(0, fixture.parseCount.get())
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `closed on disk source is not parsed by an open only endpoint`(route: Route, @TempDir dir: Path) {
        val uri = Files.writeString(dir.resolve("Closed.groovy"), "class Closed {}\n").toUri()
        val documents = DocumentProvider()
        val calls = AtomicInteger()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        calls.incrementAndGet()
                        return delegate.parse(request)
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val identifier = TextDocumentIdentifier(uri.toString())
            val answer = when (route) {
                Route.FOLDING -> service.foldingRange(FoldingRangeRequestParams(identifier)).get(5, TimeUnit.SECONDS)
                Route.SELECTION -> service.selectionRange(
                    SelectionRangeParams(identifier, listOf(Position(0, 0))),
                ).get(5, TimeUnit.SECONDS)
            }
            if (route == Route.FOLDING) assertEquals(emptyList<Any>(), answer) else assertEquals(null, answer)
            assertEquals(0, calls.get())
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `cache clearing after parsing rejects the captured request`(route: Route) {
        Fixture(route, Stage.PARSED).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `unrelated source retirement during computation rejects the answer`(route: Route) {
        Fixture(route, Stage.COMPUTING).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.invalidateCache(URI.create("file:///UnseenCompanion.groovy"))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `equal text and version in a new incarnation cannot answer the old request`(route: Route) {
        Fixture(route, Stage.COMPUTING).use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.compilation.onDocumentOpened(fixture.uri)
            fixture.documents.put(fixture.uri, fixture.source, DocumentVersion.of(7))
            fixture.release.countDown()
            assertModified(answer)
            val fresh = assertIs<List<*>>(fixture.request().get(5, TimeUnit.SECONDS))
            assertTrue(fresh.isNotEmpty(), "the new incarnation must retain its useful structural response")
        }
    }

    @Test
    fun `an empty folding answer still checks retirement during computation`() {
        Fixture(Route.FOLDING, Stage.COMPUTING, "def value = 1\n").use { fixture ->
            val answer = fixture.request()
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val error = assertThrows<ExecutionException> { answer.get(5, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    private class Fixture(
        private val route: Route,
        private val stage: Stage,
        val source: String = "class Sample {\n void run() {\n  println 1\n }\n}\n",
        sharedProvider: Boolean = true,
    ) : AutoCloseable {
        val uri: URI = URI.create("file:///LocalRange.groovy")
        val documents = DocumentProvider()
        val compilerDocuments = if (sharedProvider) documents else DocumentProvider()
        val release = CountDownLatch(1)
        val parseCount = AtomicInteger()
        private val entered = CountDownLatch(1)
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(
            documentProvider = compilerDocuments,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        parseCount.incrementAndGet()
                        val parsed = delegate.parse(request)
                        if (stage == Stage.PARSED) hold()
                        return parsed.copy(astModel = guarded(parsed.astModel))
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
            if (compilerDocuments !== documents) compilerDocuments.put(uri, source, DocumentVersion.of(7))
        }

        fun request(): CompletableFuture<*> {
            val identifier = TextDocumentIdentifier(uri.toString())
            return when (route) {
                Route.FOLDING -> service.foldingRange(FoldingRangeRequestParams(identifier))
                Route.SELECTION -> service.selectionRange(
                    SelectionRangeParams(identifier, listOf(Position(0, 7))),
                )
            }
        }

        fun awaitBarrier() {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach $stage")
        }

        private fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "request was not released at $stage" }
        }

        private fun guarded(original: GroovyAstModel): GroovyAstModel = object : GroovyAstModel by original {
            override fun getAllClassNodes(): List<ClassNode> {
                if (stage == Stage.COMPUTING && route == Route.FOLDING) hold()
                return original.getAllClassNodes()
            }

            override fun getNodes(uri: URI): List<ASTNode> {
                if (stage == Stage.COMPUTING && route == Route.SELECTION) hold()
                return original.getNodes(uri)
            }
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
