// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrossFileRequestEntryTest {
    private val uri = URI.create("file:///crossfile/Local.groovy")
    private val text = "def count = 1\nprintln count\n"
    private val declaration = Location(uri.toString(), Range(Position(0, 4), Position(0, 9)))
    private val use = Location(uri.toString(), Range(Position(1, 8), Position(1, 13)))

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `queued navigation retains the origin and caller-owned parameters`(reopen: Boolean) {
        val documents = DocumentProvider().apply { put(uri, text, DocumentVersion.of(7)) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val release = CountDownLatch(1)
            try {
                val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
                val service = GroovyTextDocumentService(scope, compilation, options)
                val entered = CountDownLatch(1)
                executor.execute {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                val document = TextDocumentIdentifier(uri.toString())
                val at = Position(1, 9)
                val context = ReferenceContext(false)
                val definition = service.definition(DefinitionParams(document, at))
                val references = service.references(ReferenceParams(document, at, context))
                assertFalse(definition.isDone || references.isDone)
                if (reopen) {
                    documents.remove(uri)
                    documents.put(uri, text, DocumentVersion.of(7))
                } else {
                    document.uri = "untitled:Other"
                    at.line = 17
                    at.character = 41
                    context.isIncludeDeclaration = true
                }
                release.countDown()
                if (reopen) {
                    for (answer in listOf(definition, references)) {
                        val failure = assertThrows<ExecutionException> { answer.get(15, TimeUnit.SECONDS) }
                        val error = assertIs<ResponseErrorException>(failure.cause).responseError
                        assertEquals(ResponseErrorCode.ContentModified.value, error.code)
                    }
                } else {
                    assertEquals(listOf(declaration), definition.get(15, TimeUnit.SECONDS).left)
                    assertEquals(listOf(use), references.get(15, TimeUnit.SECONDS))
                }
            } finally {
                release.countDown()
                scope.cancel()
                compilation.shutdown()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `one cancelled definition waiter preserves the other request's native compilation`(script: Boolean) {
        val source = if (script) text else "class Local {\nvoid inspect() {\ndef count = 1\nprintln count\n}\n}"
        val line = if (script) 0 else 2
        val expected = Location(uri.toString(), Range(Position(line, 4), Position(line, 9)))
        val documents = DocumentProvider().apply { put(uri, source) }
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = CopyOnWriteArrayList<ParseRequest>()
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { native ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri != uri) return native.parse(request)
                    invocations.add(request)
                    val parsed = native.parse(request)
                    reached.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    return parsed
                }
            }
        })
        // The initial turn of each future runs up to suspension on this calling thread.
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
        val service = GroovyTextDocumentService(scope, compilation, options)
        try {
            fun request() = service.definition(
                DefinitionParams(TextDocumentIdentifier(uri.toString()), Position(line + 1, 9)),
            )
            val first = request()
            assertTrue(reached.await(15, TimeUnit.SECONDS))
            val second = request()
            assertFalse(second.isDone)
            assertTrue(first.cancel(true))
            release.countDown()
            assertThrows<CancellationException> { first.get(15, TimeUnit.SECONDS) }
            assertEquals(listOf(expected), second.get(15, TimeUnit.SECONDS).left)
            assertEquals(setOf(source), invocations.map { it.content }.toSet())
            assertEquals(listOf(Phases.CANONICALIZATION to ParseMode.WORKSPACE), invocations.map {
                it.compilePhase to it.parseMode
            })
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
