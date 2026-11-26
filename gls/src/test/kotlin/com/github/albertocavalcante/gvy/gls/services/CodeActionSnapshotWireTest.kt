// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.UnusedImportDiagnosticProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeActionSnapshotWireTest {
    @Test
    fun `organize and unused import actions carry captured document version`() {
        Fixture().use { fixture ->
            val diagnostics = fixture.prepare()
            val organized = fixture.request(listOf(CodeActionKind.SourceOrganizeImports)).get(10, TimeUnit.SECONDS)
                .single().right
            val unused = fixture.request(listOf(CodeActionKind.QuickFix), diagnostics).get(10, TimeUnit.SECONDS)
                .single { it.right.title == "Remove unused import" }.right
            for (action in listOf(organized, unused)) {
                val edit = action.edit.documentChanges.single().left
                assertEquals(fixture.uri.toString(), edit.textDocument.uri)
                assertEquals(7, edit.textDocument.version)
                assertTrue(edit.edits.isNotEmpty())
            }
            assertEquals(
                "import java.util.ArrayList\nimport java.util.TreeMap\n\nTreeMap t = null\nArrayList a = null\n",
                apply(fixture.source, organized.edit.documentChanges.single().left.edits),
            )
            assertEquals(
                "import java.util.TreeMap\nimport java.util.ArrayList\n\nTreeMap t = null\nArrayList a = null\n",
                apply(fixture.source, unused.edit.documentChanges.single().left.edits),
            )
        }
    }

    private fun apply(text: String, edits: List<TextEdit>): String {
        val index = Utf16LineIndex(text)
        fun offset(position: Position) = index.offsetOf(position.line, position.character)
        var result = text
        for (edit in edits.sortedByDescending { offset(it.range.start) }) {
            result = result.substring(0, offset(edit.range.start)) + edit.newText + result.substring(offset(edit.range.end))
        }
        return result
    }

    @Test
    fun `same text reopen while generating organize action rejects the old answer`() {
        Fixture(pause = true).use { fixture ->
            fixture.prepare()
            val answer = fixture.request(listOf(CodeActionKind.SourceOrganizeImports))
            fixture.awaitBarrier()
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.compilation.onDocumentOpened(fixture.uri)
            fixture.documents.put(fixture.uri, fixture.source, DocumentVersion.of(7))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `worker retirement during an empty action response rejects the old answer`() {
        Fixture(pause = true).use { fixture ->
            fixture.prepare()
            val answer = fixture.request(listOf(CodeActionKind.Refactor))
            fixture.awaitBarrier()
            fixture.compilation.clearCaches()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(ResponseErrorCode.ContentModified.value, assertIs<ResponseErrorException>(error.cause).responseError.code)
    }

    private class Fixture(private val pause: Boolean = false) : AutoCloseable {
        val uri = URI.create("file:///CodeActionSnapshot.groovy")
        val source = "import java.util.TreeMap\nimport java.util.ArrayList\nimport java.util.HashMap\n\n" +
            "TreeMap t = null\nArrayList a = null\n"
        val documents = DocumentProvider()
        val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val first = AtomicBoolean(true)
        private val requests = mutableListOf<ParseRequest>()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        synchronized(requests) { requests.add(request) }
                        return delegate.parse(request)
                    }
                }
            },
        )
        private val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                formatter = Formatter { text ->
                    if (pause && first.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    text
                },
            ),
        )

        fun prepare(): List<Diagnostic> = runBlocking {
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
            compilation.compile(uri, source)
            val snapshot = assertNotNull(compilation.getCompiledSnapshot(uri))
            assertEquals(source, snapshot.sourceText)
            assertNotNull(snapshot.parseResult.ast)
            assertTrue(snapshot.parseResult.outcome.achievedPhase >= Phases.CANONICALIZATION)
            assertTrue(synchronized(requests) { requests.any { it.content == source && it.compilePhase == Phases.CANONICALIZATION } })
            val diagnostics = UnusedImportDiagnosticProvider(compilation).provideDiagnostics(uri, source).toList()
            assertEquals(1, diagnostics.size)
            diagnostics
        }

        fun request(only: List<String>, diagnostics: List<Diagnostic> = emptyList()) = service.codeAction(
            CodeActionParams(
                TextDocumentIdentifier(uri.toString()),
                Range(Position(0, 0), Position(0, 0)),
                CodeActionContext(diagnostics).apply { this.only = only },
            ),
        )

        fun awaitBarrier() {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not reach formatter")
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
