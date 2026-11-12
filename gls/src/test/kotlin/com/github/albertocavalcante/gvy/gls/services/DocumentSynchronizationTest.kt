// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun open(content: String, version: Int) =
        DidOpenTextDocumentParams(TextDocumentItem(uri.toString(), "groovy", version, content))

    private fun change(version: Int, vararg contents: String) = DidChangeTextDocumentParams(
        VersionedTextDocumentIdentifier(uri.toString(), version),
        contents.map { TextDocumentContentChangeEvent(it) },
    )
}
