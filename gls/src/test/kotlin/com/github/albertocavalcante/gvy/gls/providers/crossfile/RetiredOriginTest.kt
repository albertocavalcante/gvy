// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * When the document a definition or references request started from is edited, closed, or its compile is
 * retired before the answer is ready, the client must be told the content changed (and retry), not receive
 * an empty answer it would show as "no definition found" (#1187).
 */
class RetiredOriginTest {
    private val uri = URI.create("file:///Origin.groovy")
    private val text = "class Origin {\n    void call() { call() }\n}\n"
    private val position = Position(1, 19)
    private val documents = DocumentProvider().apply { put(uri, text, 1) }
    private val compilation = spyk(GroovyCompilationService(documentProvider = documents))

    @Test
    fun `definition reports content modified when its origin is edited meanwhile`(): Unit = runBlocking {
        editDuringCompile()
        assertContentModified { handler().getDefinitions(uri.toString(), position) }
    }

    @Test
    fun `references report content modified when its origin is edited meanwhile`(): Unit = runBlocking {
        editDuringCompile()
        assertContentModified { handler().getReferences(uri.toString(), position, includeDeclaration = true) }
    }

    @Test
    fun `definition reports content modified when its origin is closed meanwhile`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } coAnswers {
            documents.remove(uri)
            null
        }
        assertContentModified { handler().getDefinitions(uri.toString(), position) }
    }

    @Test
    fun `definition passes on content modified when its origin compile keeps being retired`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } throws
            ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "kept retiring", null))
        assertContentModified { handler().getDefinitions(uri.toString(), position) }
    }

    @Test
    fun `implementation reports content modified when its origin is edited meanwhile`(): Unit = runBlocking {
        editDuringCompile()
        assertContentModified { handler().getImplementations(uri.toString(), position) }
    }

    @Test
    fun `a retirement of another document does not fail the request`(): Unit = runBlocking {
        val other = URI.create("file:///Other.groovy")
        documents.put(other, "class Other {}", 1)
        coEvery { compilation.ensureCompiled(other, any<DocumentSnapshot>()) } throws
            ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "other kept changing", null))

        val definitions = handler().getDefinitions(uri.toString(), position)

        assertTrue(definitions.left.isNotEmpty(), "only a changed origin may turn the answer into ContentModified")
    }

    @Test
    fun `cancelling the request itself still cancels it`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } coAnswers { awaitCancellation() }
        val request = async(start = CoroutineStart.UNDISPATCHED) { handler().getDefinitions(uri.toString(), position) }
        request.cancel()
        assertFailsWith<CancellationException> { request.await() }
    }

    @Test
    fun `an unchanged origin still answers`(): Unit = runBlocking {
        val definitions = handler().getDefinitions(uri.toString(), position)
        assertTrue(definitions.left.isNotEmpty(), "control: the same request answers when nothing changed")
    }

    private fun editDuringCompile() {
        coEvery { compilation.ensureCompiled(uri) } coAnswers {
            documents.update(uri, text.replace("call()", "other()"), 2)
            callOriginal()
        }
    }

    private suspend fun assertContentModified(request: suspend () -> Any) {
        val error = assertFailsWith<ResponseErrorException> { request() }
        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    private fun handler() = CrossFileOperationsHandler(
        compilation,
        documents,
        mockk<SourceNavigator>(relaxed = true),
        { false },
        {},
    )
}
