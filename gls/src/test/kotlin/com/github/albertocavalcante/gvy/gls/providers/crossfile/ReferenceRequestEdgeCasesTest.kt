// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.references.ReferenceProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.gvy.source.DocumentVersion
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Pins what a references request does NOT turn into an error: cancellation, ContentModified, and the benign
 * conditions that legitimately yield an empty or partial-by-design answer. A change that made any of those an
 * InternalError would pop an error up in the client for something that is not a server bug.
 */
class ReferenceRequestEdgeCasesTest {
    private val uri = URI.create("file:///Edge.groovy")
    private val text = "class Edge {\n    void helper() {}\n    void run() { helper() }\n}\n"
    private val callOfHelper = Position(2, 19)
    private val documents = DocumentProvider().apply { put(uri, text, DocumentVersion.of(1)) }
    private val compilation = spyk(GroovyCompilationService(documentProvider = documents))

    private fun handler() = CrossFileOperationsHandler(
        compilation,
        documents,
        mockk<SourceNavigator>(relaxed = true),
        { false },
        {},
    )

    @Test
    fun `a cancelled scan stays cancelled at the provider`(): Unit = runBlocking {
        compilation.compile(uri, text)
        val visitor = assertNotNull(compilation.getAstModel(uri))
        mockkObject(visitor)
        try {
            every { visitor.getAllNodes() } throws CancellationException("cancelled")

            // The flow may re-create the exception (stack trace recovery); it must stay a cancellation.
            val failure = assertFailsWith<CancellationException> {
                ReferenceProvider(compilation).provideReferences(uri.toString(), callOfHelper, true).toList()
            }
            assertEquals("cancelled", failure.message)
        } finally {
            unmockkObject(visitor)
            compilation.clearCaches()
        }
    }

    @Test
    fun `a cancelled scan stays cancelled at the handler`(): Unit = runBlocking {
        compilation.compile(uri, text)
        val visitor = assertNotNull(compilation.getAstModel(uri))
        mockkObject(visitor)
        try {
            every { visitor.getAllNodes() } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> { handler().getReferences(uri.toString(), callOfHelper, true) }
        } finally {
            unmockkObject(visitor)
            compilation.clearCaches()
        }
    }

    @Test
    fun `content modified passes through the references handler unchanged`(): Unit = runBlocking {
        val modified = ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "kept changing", null))
        coEvery { compilation.ensureCompiled(uri) } throws modified

        val failure =
            assertFailsWith<ResponseErrorException> { handler().getReferences(uri.toString(), callOfHelper, true) }

        assertSame(modified, failure)
    }

    @Test
    fun `an unopened file answers with no references, not an error`(): Unit = runBlocking {
        val references = handler().getReferences("file:///NeverOpened.groovy", Position(0, 0), true)
        assertEquals(emptyList(), references)
    }

    @Test
    fun `a non-file uri answers with no references, not an error`(): Unit = runBlocking {
        val references = handler().getReferences("untitled:Scratch.groovy", Position(0, 0), true)
        assertEquals(emptyList(), references)
    }

    @Test
    fun `a missing index document leaves the document's own references, not an error`(): Unit = runBlocking {
        compilation.compile(uri, text)
        val index = mockk<WorkspaceSymbolIndex>()
        every { index.getDocumentSnapshot(any()) } returns null

        val references = ReferenceProvider(compilation, index).provideReferences(uri.toString(), callOfHelper, true)
            .toList()

        assertEquals(2, references.size, "the declaration and the call, from the document itself")
        compilation.clearCaches()
    }

    @Test
    fun `an index document without source text leaves the document's own references, not an error`(): Unit =
        runBlocking {
            compilation.compile(uri, text)
            val index = mockk<WorkspaceSymbolIndex>()
            every { index.getDocumentSnapshot(any()) } returns SemanticDocumentSnapshot(mockk(relaxed = true), null)

            val references = ReferenceProvider(compilation, index)
                .provideReferences(uri.toString(), callOfHelper, true)
                .toList()

            assertEquals(2, references.size)
            compilation.clearCaches()
        }
}
