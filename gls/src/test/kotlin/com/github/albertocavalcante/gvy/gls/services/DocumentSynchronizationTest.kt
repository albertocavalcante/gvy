// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.DiagnosticConfig
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSynchronizationTest {
    private val uri = URI.create("file:///workspace/Example.groovy")

    private class Fixture(scope: CoroutineScope) {
        val documents = DocumentProvider()
        val service = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = mockk<GroovyCompilationService>(relaxed = true),
            options = GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
    }

    @Test
    fun `older change cannot overwrite the current buffer`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", 3))
        fixture.service.didChange(change(8, "latest"))
        fixture.service.didChange(change(5, "obsolete"))

        assertEquals("latest", fixture.documents.get(uri))
        assertEquals(DocumentSnapshot("latest", 8), fixture.documents.getSnapshot(uri))
    }

    @Test
    fun `duplicate version cannot replace an accepted change`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", 3))
        fixture.service.didChange(change(4, "accepted"))
        fixture.service.didChange(change(4, "duplicate"))

        assertEquals("accepted", fixture.documents.get(uri))
    }

    @Test
    fun `change after close does not recreate an open buffer`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", 3))
        fixture.service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))
        fixture.service.didChange(change(4, "late change"))

        assertNull(fixture.documents.get(uri))
    }

    @Test
    fun `multiple full content changes use the final state in notification order`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", 3))
        fixture.service.didChange(change(4, "intermediate", "final"))

        assertEquals("final", fixture.documents.get(uri))
        assertEquals(DocumentSnapshot("final", 4), fixture.documents.getSnapshot(uri))
    }

    @Test
    fun `open and empty change retain the client version`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", -2))
        assertEquals(DocumentSnapshot("initial", -2), fixture.documents.getSnapshot(uri))

        fixture.service.didChange(change(7))

        assertEquals(DocumentSnapshot("initial", 7), fixture.documents.getSnapshot(uri))
    }

    @Test
    fun `ranged notification cannot truncate a full sync buffer`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", 3))
        val rangedChange = TextDocumentContentChangeEvent("x").apply {
            range = Range(Position(0, 0), Position(0, 1))
        }
        fixture.service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri.toString(), 4),
                listOf(TextDocumentContentChangeEvent("intermediate"), rangedChange),
            ),
        )

        assertEquals(DocumentSnapshot("initial", 3), fixture.documents.getSnapshot(uri))
    }

    @Test
    fun `missing change version cannot replace a versioned buffer`(): Unit = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.service.didOpen(open("initial", 3))
        fixture.service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri.toString(), null),
                listOf(TextDocumentContentChangeEvent("invalid")),
            ),
        )

        assertEquals(DocumentSnapshot("initial", 3), fixture.documents.getSnapshot(uri))
    }

    @Test
    fun `save diagnoses the current snapshot immediately and ignores supplied save text`(): Unit = runTest {
        val compilation = mockk<GroovyCompilationService>(relaxed = true)
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        every { compilation.workspaceManager } returns mockk(relaxed = true)
        val documents = DocumentProvider()
        val service = GroovyTextDocumentService(
            coroutineScope = backgroundScope,
            compilationService = compilation,
            options = GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        service.didOpen(open("initial", 3))
        service.didChange(change(4, "current buffer"))
        runCurrent()

        service.didSave(
            DidSaveTextDocumentParams(TextDocumentIdentifier(uri.toString())).apply {
                text = "stale client save text"
            },
        )
        runCurrent()

        coVerify(exactly = 1) { compilation.compileAsync(any(), uri, "current buffer") }
        coVerify(exactly = 0) { compilation.compileAsync(any(), uri, "stale client save text") }
        assertEquals(DocumentSnapshot("current buffer", 4), documents.getSnapshot(uri))

        advanceTimeBy(250)
        advanceUntilIdle()
        coVerify(exactly = 1) { compilation.compileAsync(any(), uri, "current buffer") }
    }

    private inner class StaleSaveFixture(scope: CoroutineScope) {
        val oldContent = "import demo."
        val repairedContent = "import demo.Name"
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val documents = DocumentProvider()
        val compilation = mockk<GroovyCompilationService>(relaxed = true)
        val service: GroovyTextDocumentService

        init {
            val client = mockk<LanguageClient>()
            every { client.publishDiagnostics(capture(publications)) } just Runs
            every { compilation.workspaceManager } returns mockk(relaxed = true)
            every { compilation.compileAsync(any(), uri, oldContent) } returns oldCompilation()
            every { compilation.compileAsync(any(), uri, repairedContent) } returns CompletableDeferred(cleanResult())
            service = GroovyTextDocumentService(
                coroutineScope = scope,
                compilationService = compilation,
                options = GroovyTextDocumentServiceOptions(
                    serverConfiguration = ServerConfiguration(
                        diagnosticConfig = DiagnosticConfig(
                            disabledProviders = setOf("codenarc", "custom-rules", "unused-imports"),
                        ),
                    ),
                    documentProvider = documents,
                    client = { client },
                ),
            )
        }

        private fun oldCompilation(): Deferred<CompilationResult> {
            val oldError = Diagnostic(Range(Position(0, 11), Position(0, 12)), "Incomplete import").apply {
                severity = DiagnosticSeverity.Error
                code = Either.forLeft("syntax-error")
            }
            val result = mockk<CompilationResult>()
            every { result.diagnostics } returns listOf(oldError)
            every { result.parseCompleted } returns true
            val deferred = mockk<Deferred<CompilationResult>>()
            coEvery { deferred.await() } coAnswers {
                withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                    oldReturned.complete(Unit)
                    result
                }
            }
            return deferred
        }

        private fun cleanResult(): CompilationResult = mockk<CompilationResult>().also { result ->
            every { result.diagnostics } returns emptyList()
            every { result.parseCompleted } returns true
            every { result.sourceText } returns repairedContent
        }
    }

    @Test
    fun `save publishes the repaired version before obsolete compilation returns`(): Unit = runTest {
        val fixture = StaleSaveFixture(backgroundScope)
        fixture.service.didOpen(open(fixture.oldContent, 1))
        runCurrent()
        try {
            assertTrue(fixture.oldStarted.isCompleted, "The obsolete compile must be in flight before the repair")
            fixture.service.didChange(change(2, fixture.repairedContent))
            fixture.service.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri.toString())))
            runCurrent()
            coVerify(exactly = 1) { fixture.compilation.compileAsync(any(), uri, fixture.repairedContent) }
            assertEquals(DocumentSnapshot(fixture.repairedContent, 2), fixture.documents.getSnapshot(uri))
            assertEquals(1, fixture.publications.size, "Save must publish before the debounce expires")
            assertEquals(2, fixture.publications.single().version)
            assertTrue(fixture.publications.single().diagnostics.isEmpty())
        } finally {
            fixture.releaseOld.complete(Unit)
            runCurrent()
        }
        assertTrue(fixture.oldReturned.isCompleted, "The obsolete compiler must finish after the clean publication")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(1, fixture.publications.size, "The obsolete error and retired debounce must not publish")
        assertEquals(2, fixture.publications.single().version)
        assertTrue(fixture.publications.single().diagnostics.isEmpty())
    }

    @Test
    fun `save for an unknown or closed document does not compile`(): Unit = runTest {
        val compilation = mockk<GroovyCompilationService>(relaxed = true)
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        every { compilation.workspaceManager } returns mockk(relaxed = true)
        val service = GroovyTextDocumentService(
            coroutineScope = backgroundScope,
            compilationService = compilation,
        )
        val save = DidSaveTextDocumentParams(TextDocumentIdentifier(uri.toString()))

        service.didSave(save)
        service.didOpen(open("opened", 1))
        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))
        service.didSave(save)
        runCurrent()
        advanceTimeBy(250)
        advanceUntilIdle()

        coVerify(exactly = 0) { compilation.compileAsync(any(), any(), any()) }
    }

    private fun open(content: String, version: Int) =
        DidOpenTextDocumentParams(TextDocumentItem(uri.toString(), "groovy", version, content))

    private fun change(version: Int, vararg contents: String) = DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(uri.toString(), version),
        contents.map { TextDocumentContentChangeEvent(it) },
    )
}
