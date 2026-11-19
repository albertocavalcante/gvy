// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompilationEnsurerTest {
    private val first = URI.create("file:///First.groovy")
    private val second = URI.create("file:///Second.groovy")
    private val documents = mockk<DocumentProvider>()
    private val indexing = mockk<SymbolIndexingService> {
        // Readiness markers (#1344): a real, empty registry
        every { unparseable } returns UnparseableTexts(ArtifactOwnership())
    }
    private val compilation = mockk<GroovyCompilationService> {
        every { symbolIndexingService } returns indexing
    }
    private val snapshots = mutableMapOf<URI, DocumentSnapshot>()

    private fun open(vararg uris: URI) {
        every { documents.getAllUris() } returns uris.toSet()
        uris.forEach { uri ->
            val snapshot = DocumentSnapshot("class ${uri.path.drop(1).substringBefore('.')} {}", 1)
            snapshots[uri] = snapshot
            every { documents.getSnapshot(uri) } returns snapshot
        }
    }

    private fun compiled(withAst: Boolean = true) = CompilationResult(
        withAst,
        if (withAst) mockk<ModuleNode>() else null,
        emptyList(),
        sourceAnnotations = SourceAnnotationIndex.UNAVAILABLE,
    )

    @Test
    fun `documents indexed from their current text are not compiled again`(): Unit = runBlocking {
        open(first, second)
        every { indexing.isIndexed(any(), any()) } returns true

        CompilationEnsurer(documents, compilation).ensureAllCompiled()

        coVerify(exactly = 0) { compilation.ensureCompiled(any(), any<DocumentSnapshot>()) }
    }

    @Test
    fun `a document that is not current is compiled from its current snapshot`(): Unit = runBlocking {
        open(first, second)
        every { indexing.isIndexed(first, any()) } returns true
        every { indexing.isIndexed(second, any()) } returnsMany listOf(false, true)
        coEvery { compilation.ensureCompiled(second, any<DocumentSnapshot>()) } returns compiled()

        CompilationEnsurer(documents, compilation).ensureAllCompiled()

        coVerify(exactly = 1) { compilation.ensureCompiled(second, snapshots.getValue(second)) }
        coVerify(exactly = 0) { compilation.ensureCompiled(first, any<DocumentSnapshot>()) }
    }

    @Test
    fun `a compile whose writes were discarded is retried once`(): Unit = runBlocking {
        open(first)
        every { indexing.isIndexed(first, any()) } returns false // retired after it completed
        coEvery { compilation.ensureCompiled(first, any<DocumentSnapshot>()) } returns compiled()

        CompilationEnsurer(documents, compilation).ensureAllCompiled()

        coVerify(exactly = 2) { compilation.ensureCompiled(first, any<DocumentSnapshot>()) }
    }

    @Test
    fun `a buffer without an AST is compiled once per request`(): Unit = runBlocking {
        open(first)
        every { indexing.isIndexed(first, any()) } returns false
        coEvery { compilation.ensureCompiled(first, any<DocumentSnapshot>()) } returns compiled(withAst = false)

        CompilationEnsurer(documents, compilation).ensureAllCompiled()

        coVerify(exactly = 1) { compilation.ensureCompiled(first, any<DocumentSnapshot>()) }
    }

    @Test
    fun `a failing or churning document does not stop the others`(): Unit = runBlocking {
        open(first, second)
        every { indexing.isIndexed(any(), any()) } returnsMany listOf(false, false, true)
        coEvery { compilation.ensureCompiled(first, any<DocumentSnapshot>()) } throws
            ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "churn", null))
        coEvery { compilation.ensureCompiled(second, any<DocumentSnapshot>()) } returns compiled()

        CompilationEnsurer(documents, compilation).ensureAllCompiled()

        coVerify(exactly = 1) { compilation.ensureCompiled(second, any<DocumentSnapshot>()) }
    }

    @Test
    fun `a closed document is skipped`(): Unit = runBlocking {
        every { documents.getAllUris() } returns setOf(first)
        every { documents.getSnapshot(first) } returns null

        CompilationEnsurer(documents, compilation).ensureAllCompiled()

        coVerify(exactly = 0) { compilation.ensureCompiled(any(), any<DocumentSnapshot>()) }
    }

    @Test
    fun `waiting is bounded by the overall timeout`(): Unit = runBlocking {
        open(first)
        every { indexing.isIndexed(any(), any()) } returns false
        coEvery { compilation.ensureCompiled(first, any<DocumentSnapshot>()) } coAnswers { awaitCancellation() }

        val start = System.currentTimeMillis()
        CompilationEnsurer(documents, compilation, maxTimeMs = 200).ensureAllCompiled()

        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 2_000, "should give up after its bound, took ${elapsed}ms")
    }

    @Test
    fun `cancelling the request cancels the wait`(): Unit = runBlocking {
        open(first)
        every { indexing.isIndexed(any(), any()) } returns false
        val compiling = CompletableDeferred<Unit>()
        coEvery { compilation.ensureCompiled(first, any<DocumentSnapshot>()) } coAnswers {
            compiling.complete(Unit)
            awaitCancellation()
        }

        val request = async(start = CoroutineStart.UNDISPATCHED) {
            CompilationEnsurer(documents, compilation).ensureAllCompiled()
        }
        compiling.await()
        request.cancel()

        assertFailsWith<CancellationException> { request.await() }
    }
}
