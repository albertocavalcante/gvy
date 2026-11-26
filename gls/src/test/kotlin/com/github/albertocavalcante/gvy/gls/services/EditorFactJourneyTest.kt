// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.ast.types.Position as AstPosition

@Isolated("Temporarily enables debug logging for the text document service")
class EditorFactJourneyTest {
    enum class Direction { INCOMING, OUTGOING }

    @Test
    fun `highlight worker failure propagates while a genuine empty answer remains empty`() {
        Fixture("// no symbols\n").use { fixture ->
            val params = DocumentHighlightParams(TextDocumentIdentifier(fixture.uri.toString()), Position(0, 1))
            assertTrue(fixture.service.documentHighlight(params).get(10, TimeUnit.SECONDS).isEmpty())
        }
        Fixture("def target = 1\n").use { fixture ->
            fixture.workerFailure = IOException("controlled parse failure")
            val params = DocumentHighlightParams(TextDocumentIdentifier(fixture.uri.toString()), Position(0, 4))
            val error =
                assertThrows<ExecutionException> { fixture.service.documentHighlight(params).get(10, TimeUnit.SECONDS) }
            assertEquals("controlled parse failure", assertIs<IOException>(error.cause).message)
        }
    }

    @Test
    fun `cross file incoming identifies the caller and its exact call range`() {
        Fixture("class Target { def work() {} }\n").use { fixture ->
            val caller = fixture.openCaller("class Caller { def run(Target x) { x.work() } }\n")
            val item = fixture.prepare(Position(0, 20))
            val result = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                .get(10, TimeUnit.SECONDS)
            assertEquals(listOf("run"), result.map { it.from.name })
            assertEquals(caller.toString(), result.single().from.uri)
            assertEquals(listOf(Range(Position(0, 37), Position(0, 41))), result.single().fromRanges)
        }
    }

    @Test
    fun `retirement while resolving a cross file caller rejects the entire answer`() {
        Fixture("class Target { def work() {} }\n").use { fixture ->
            val caller = fixture.openCaller("class Caller { def run(Target x) { x.work() } }\n")
            val item = fixture.prepare(Position(0, 20))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.modelAction = { uri ->
                if (uri == caller) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            try {
                val answer = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                assertTrue(entered.await(10, TimeUnit.SECONDS), "caller lookup did not start")
                fixture.retireUnrelated()
                release.countDown()
                assertError(answer, ResponseErrorCode.ContentModified)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `an unresolvable indexed caller fails rather than returning partial incoming calls`() {
        Fixture("class Target { def work() {} }\n").use { fixture ->
            val caller = fixture.openCaller("class Caller { def run(Target x) { x.work() } }\n")
            val item = fixture.prepare(Position(0, 20))
            fixture.hiddenNodeUri = caller
            val answer = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
            assertError(answer, ResponseErrorCode.RequestFailed)
        }
    }

    @ParameterizedTest
    @EnumSource(Direction::class)
    fun `a prepared item cannot be reinterpreted after an edit`(direction: Direction) {
        Fixture("def target() {}\ndef caller() { target() }\n").use { fixture ->
            val item = fixture.prepare(Position(0, 5))
            fixture.documents.update(
                fixture.uri,
                "def hacker() {}\ndef caller() { hacker() }\n",
                DocumentVersion.of(8),
            )
            val answer = when (direction) {
                Direction.INCOMING -> fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                Direction.OUTGOING -> fixture.service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item))
            }
            assertError(answer, ResponseErrorCode.ContentModified)
        }
    }

    @ParameterizedTest
    @EnumSource(Direction::class)
    fun `a prepared item cannot cross an equal text and version reopen`(direction: Direction) {
        val source = "def target() {}\ndef caller() { target() }\n"
        Fixture(source).use { fixture ->
            val item = fixture.prepare(Position(0, 5))
            fixture.reopen(source)
            val answer = when (direction) {
                Direction.INCOMING -> fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                Direction.OUTGOING -> fixture.service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item))
            }
            assertError(answer, ResponseErrorCode.ContentModified)
        }
    }

    @ParameterizedTest
    @EnumSource(Direction::class)
    fun `wire item data remains usable and detects an edit`(direction: Direction) {
        val source = "def target() {}\ndef caller() { target() }\n"
        Fixture(source).use { fixture ->
            val item = fixture.prepare(if (direction == Direction.INCOMING) Position(0, 5) else Position(1, 5))
            val gson = MessageJsonHandler(emptyMap()).gson
            val echoed = gson.fromJson(gson.toJson(item), CallHierarchyItem::class.java)
            assertTrue(assertIs<JsonPrimitive>(echoed.data).isString)
            when (direction) {
                Direction.INCOMING -> {
                    val current = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(echoed))
                        .get(10, TimeUnit.SECONDS)
                    assertEquals(listOf("caller"), current.map { it.from.name })
                }
                Direction.OUTGOING -> {
                    val current = fixture.service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(echoed))
                        .get(10, TimeUnit.SECONDS)
                    assertEquals(listOf("target"), current.map { it.to.name })
                }
            }
            fixture.documents.update(fixture.uri, "def hacker() {}\ndef caller() { hacker() }\n", DocumentVersion.of(8))
            val stale = when (direction) {
                Direction.INCOMING -> fixture.service.callHierarchyIncomingCalls(
                    CallHierarchyIncomingCallsParams(echoed),
                )
                Direction.OUTGOING -> fixture.service.callHierarchyOutgoingCalls(
                    CallHierarchyOutgoingCallsParams(echoed),
                )
            }
            assertError(stale, ResponseErrorCode.ContentModified)
        }
    }

    @ParameterizedTest
    @EnumSource(Direction::class)
    fun `non-string wire data is rejected`(direction: Direction) {
        Fixture("def target() {}\ndef caller() { target() }\n").use { fixture ->
            for (invalid in listOf(JsonObject(), JsonArray(), JsonPrimitive(42), JsonPrimitive(true))) {
                val item = fixture.prepare(Position(0, 5))
                item.data = invalid
                val result = when (direction) {
                    Direction.INCOMING -> fixture.service.callHierarchyIncomingCalls(
                        CallHierarchyIncomingCallsParams(item),
                    )
                    Direction.OUTGOING -> fixture.service.callHierarchyOutgoingCalls(
                        CallHierarchyOutgoingCallsParams(item),
                    )
                }
                assertError(result, ResponseErrorCode.RequestFailed)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Direction::class)
    fun `closed item rejects changed disk text even before watcher notification`(
        direction: Direction,
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("Closed.groovy")
        val original = "def target() {}\ndef caller() { target() }\n"
        Files.writeString(file, original)
        Fixture(original, uri = file.toUri(), open = false).use { fixture ->
            val item = fixture.prepare(if (direction == Direction.INCOMING) Position(0, 5) else Position(1, 5))
            Files.writeString(file, "def hacker() {}\ndef caller() { hacker() }\n")
            val answer = when (direction) {
                Direction.INCOMING -> fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                Direction.OUTGOING -> fixture.service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item))
            }
            assertError(answer, ResponseErrorCode.ContentModified)
        }
    }

    private fun assertError(answer: CompletableFuture<*>, code: ResponseErrorCode) {
        val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(code.value, assertIs<ResponseErrorException>(error.cause).responseError.code)
    }

    @Test
    fun `cold prepared callers and targets lead to matching outgoing and incoming calls`() {
        val source = "def target() {}\ndef caller() { target() }\n"
        Fixture(source).use { fixture ->
            val caller = fixture.prepare(Position(1, 5))
            val outgoing = fixture.service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(caller))
                .get(10, TimeUnit.SECONDS)
            assertEquals(listOf("target"), outgoing.map { it.to.name })
            assertEquals(listOf(Range(Position(1, 15), Position(1, 23))), outgoing.single().fromRanges)

            val target = fixture.prepare(Position(0, 5))
            val incoming = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(target))
                .get(10, TimeUnit.SECONDS)
            assertEquals(listOf("caller"), incoming.map { it.from.name })
            assertEquals(listOf(Range(Position(1, 15), Position(1, 21))), incoming.single().fromRanges)
        }
    }

    @Test
    fun `preparing after an edit uses current rather than old hierarchy inputs`() {
        val oldSource = "def oldTarget() {}\ndef caller() { oldTarget() }\n"
        val current = "def target() {}\ndef caller() { target() }\n"
        Fixture(oldSource).use { fixture ->
            fixture.seedLegacy(oldSource)
            fixture.documents.update(fixture.uri, current, DocumentVersion.of(8))
            val caller = fixture.prepare(Position(1, 5))
            val outgoing = fixture.service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(caller))
                .get(10, TimeUnit.SECONDS)
            assertEquals(listOf("target"), outgoing.map { it.to.name })
            val target = fixture.prepare(Position(0, 5))
            val incoming = fixture.service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(target))
                .get(10, TimeUnit.SECONDS)
            assertEquals(listOf("caller"), incoming.map { it.from.name })
        }
    }

    @Test
    fun `debug logging uses captured request values after the caller mutates parameters`() {
        val logger = LoggerFactory.getLogger(GroovyTextDocumentService::class.java) as Logger
        val previousLevel = logger.level
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    block.run()
                }
            }
        }
        logger.level = Level.DEBUG
        logger.addAppender(logs)
        try {
            Fixture("def counter = 0\ncounter = counter + 1\nprintln counter\n", dispatcher).use { fixture ->
                val params = DocumentHighlightParams(TextDocumentIdentifier(fixture.uri.toString()), Position(0, 4))
                val future = fixture.service.documentHighlight(params)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                params.textDocument = TextDocumentIdentifier("file:///Changed.groovy")
                params.position = Position(12, 34)
                release.countDown()
                val result = future.get(10, TimeUnit.SECONDS)
                assertEquals(
                    setOf(
                        Range(Position(0, 4), Position(0, 11)),
                        Range(Position(1, 0), Position(1, 7)),
                        Range(Position(1, 10), Position(1, 17)),
                        Range(Position(2, 8), Position(2, 15)),
                    ),
                    result.map { it.range }.toSet(),
                )
                assertTrue(
                    logs.list.any { it.formattedMessage == "Document highlight requested for ${fixture.uri} at 0:4" },
                )
            }
        } finally {
            release.countDown()
            logger.detachAppender(logs)
            logs.stop()
            logger.level = previousLevel
        }
    }

    private class Fixture(
        source: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        val uri: URI = URI.create("file:///EditorFactJourney.groovy"),
        open: Boolean = true,
    ) : AutoCloseable {
        val documents = DocumentProvider().apply { if (open) put(uri, source, DocumentVersion.of(7)) }

        @Volatile var modelAction: (URI) -> Unit = {}

        @Volatile var hiddenNodeUri: URI? = null

        @Volatile var workerFailure: IOException? = null
        private val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        workerFailure?.let { throw it }
                        val parsed = delegate.parse(request)
                        val model = parsed.astModel
                        return parsed.copy(
                            astModel = object : GroovyAstModel by model {
                                override fun getNodeAt(uri: URI, position: AstPosition): ASTNode? {
                                    modelAction(uri)
                                    return if (uri == hiddenNodeUri) null else model.getNodeAt(uri, position)
                                }
                            },
                        )
                    }
                }
            },
        )
        private val scope = CoroutineScope(dispatcher + SupervisorJob())
        val service =
            GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )

        init {
            if (open) compilation.onDocumentOpened(uri)
        }

        fun prepare(position: Position) = service.prepareCallHierarchy(
            CallHierarchyPrepareParams(TextDocumentIdentifier(uri.toString()), position),
        ).get(10, TimeUnit.SECONDS).single()

        fun seedLegacy(source: String) = runBlocking { compilation.compile(uri, source) }

        fun openCaller(source: String): URI {
            val caller = URI.create("file:///EditorFactCaller.groovy")
            compilation.onDocumentOpened(caller)
            documents.put(caller, source, DocumentVersion.of(7))
            return caller
        }

        fun retireUnrelated() = compilation.invalidateCache(URI.create("file:///UnseenCompanion.groovy"))

        fun reopen(source: String) {
            documents.remove(uri)
            compilation.onClosedSourceChanged(uri)
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
        }

        override fun close() {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
