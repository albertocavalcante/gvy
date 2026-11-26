// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorFactBoundaryTest {
    enum class Route { HIGHLIGHT, PREPARE }
    enum class Change { EDIT, REOPEN, RETIRE }
    enum class Authority { DIFFERENT, EQUAL, NONOVERLAPPING }

    @ParameterizedTest
    @CsvSource(
        "HIGHLIGHT, EDIT",
        "HIGHLIGHT, REOPEN",
        "HIGHLIGHT, RETIRE",
        "PREPARE, EDIT",
        "PREPARE, REOPEN",
        "PREPARE, RETIRE",
    )
    fun `queued request cannot answer from an edited or reopened buffer or retired workspace`(
        route: Route,
        change: Change,
    ) {
        val uri = URI.create("file:///QueuedEditorFact.groovy")
        val source = "def target() {}\ntarget()\n"
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(7)) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    block.run()
                }
            }
        }
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            compilation.onDocumentOpened(uri)
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            val identifier = TextDocumentIdentifier(uri.toString())
            val answer = when (route) {
                Route.HIGHLIGHT -> service.documentHighlight(DocumentHighlightParams(identifier, Position(0, 5)))
                Route.PREPARE -> service.prepareCallHierarchy(CallHierarchyPrepareParams(identifier, Position(0, 5)))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request was not queued")
            when (change) {
                Change.EDIT -> documents.update(uri, "def hacker() {}\nhacker()\n", DocumentVersion.of(8))
                Change.REOPEN -> {
                    documents.remove(uri)
                    compilation.onClosedSourceChanged(uri)
                    compilation.onDocumentOpened(uri)
                    documents.put(uri, source, DocumentVersion.of(7))
                }
                Change.RETIRE -> compilation.clearCaches()
            }
            release.countDown()
            assertProtocolFailure(answer, ResponseErrorCode.ContentModified)
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "HIGHLIGHT, DIFFERENT",
        "HIGHLIGHT, EQUAL",
        "HIGHLIGHT, NONOVERLAPPING",
        "PREPARE, DIFFERENT",
        "PREPARE, EQUAL",
        "PREPARE, NONOVERLAPPING",
    )
    fun `mismatched service and compiler document authorities never return another buffer`(
        route: Route,
        authority: Authority,
    ) {
        val uri = URI.create("file:///Authority.groovy")
        val source = "def target() {}\ntarget()\n"
        val endpointDocuments = DocumentProvider().apply { put(uri, source, DocumentVersion.of(7)) }
        val compilerDocuments = DocumentProvider().apply {
            when (authority) {
                Authority.DIFFERENT -> put(uri, "def hacker() {}\nhacker()\n", DocumentVersion.of(7))
                Authority.EQUAL -> put(uri, source, DocumentVersion.of(7))
                Authority.NONOVERLAPPING -> put(URI.create("file:///Other.groovy"), source, DocumentVersion.of(7))
            }
        }
        val compilation = GroovyCompilationService(documentProvider = compilerDocuments)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = endpointDocuments),
                )
            val identifier = TextDocumentIdentifier(uri.toString())
            val answer = when (route) {
                Route.HIGHLIGHT -> service.documentHighlight(DocumentHighlightParams(identifier, Position(0, 5)))
                Route.PREPARE -> service.prepareCallHierarchy(CallHierarchyPrepareParams(identifier, Position(0, 5)))
            }
            assertProtocolFailure(answer, ResponseErrorCode.ContentModified)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `explicit receiver call does not prepare an unrelated same name local method`() {
        val source = "class C { def size() {} ; def run() { 'abc'.size() } }"
        val uri = URI.create("file:///Receiver.groovy")
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(1)) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            val result = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(
                    TextDocumentIdentifier(uri.toString()),
                    Position(
                        0,
                        source.lastIndexOf("size") + 1,
                    ),
                ),
            ).get(10, TimeUnit.SECONDS)
            assertTrue(result.isEmpty(), "an explicit String receiver cannot resolve to C.size: $result")
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `implicit script method call still prepares its declaration`() {
        val source = "def target() {}\ntarget()\n"
        val uri = URI.create("file:///ScriptCall.groovy")
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(1)) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            val item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(uri.toString()), Position(1, 2)),
            ).get(10, TimeUnit.SECONDS).single()
            assertEquals("target", item.name)
            assertEquals(uri.toString(), item.uri)
            assertEquals(Range(Position(0, 4), Position(0, 10)), item.selectionRange)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `outgoing foreign constructor never claims the caller file as its declaration`() {
        val source = "class Consumer { def run() { new Other() } }\n"
        val uri = URI.create("file:///Consumer.groovy")
        val other = URI.create("file:///Other.groovy")
        val documents = DocumentProvider().apply {
            put(uri, source, DocumentVersion.of(1))
            put(other, "class Other {}\n", DocumentVersion.of(1))
        }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service =
                GroovyTextDocumentService(
                    scope,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
            val item = service.prepareCallHierarchy(
                CallHierarchyPrepareParams(
                    TextDocumentIdentifier(uri.toString()),
                    Position(0, source.indexOf("run") + 1),
                ),
            ).get(10, TimeUnit.SECONDS).single()
            val result = try {
                service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item)).get(10, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                assertEquals(
                    ResponseErrorCode.RequestFailed.value,
                    (error.cause as ResponseErrorException).responseError.code,
                )
                return
            }
            val target = result.single().to
            assertEquals("Other", target.name)
            assertEquals(other.toString(), target.uri)
            assertEquals(Range(Position(0, 6), Position(0, 11)), target.selectionRange)
            assertNotNull(target.data)
            service.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(target)).get(10, TimeUnit.SECONDS)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun assertProtocolFailure(answer: CompletableFuture<*>, code: ResponseErrorCode) {
        val failure = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(code.value, (failure.cause as ResponseErrorException).responseError.code)
    }
}
