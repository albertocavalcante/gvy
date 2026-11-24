// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.crossfile

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionStatus
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionTelemetryEvent
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Definition, implementation and the link path answer "nothing found" only when nothing was found: a lookup that
 * failed is an InternalError response, as for references, and benign misses stay empty (#2427).
 */
class LookupFailureResponseTest {
    private val uri = URI.create("file:///Lookup.groovy")
    private val text = "class Lookup {\n    void call() { call() }\n}\nprintln \"literal\"\n"
    private val onCall = Position(1, 19)
    private val onLiteral = Position(3, 10)
    private val onMissingImport = Position(0, 22)
    private val documents = DocumentProvider().apply { put(uri, text, 1) }
    private val compilation = spyk(GroovyCompilationService(documentProvider = documents))
    private val events = mutableListOf<DefinitionTelemetryEvent>()

    @Test
    fun `definition fails when compilation fails`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } throws IllegalStateException("compile broke")
        assertInternalError("compile broke") { handler().getDefinitions(uri.toString(), onCall) }
    }

    @Test
    fun `definition fails when resolution fails`(): Unit = runBlocking {
        failResolutionAfterCompile()
        assertInternalError("ast unavailable") { handler().getDefinitions(uri.toString(), onCall) }
    }

    @Test
    fun `definition links fail when resolution fails`(): Unit = runBlocking {
        failResolutionAfterCompile()
        assertInternalError("ast unavailable") { handler(links = true).getDefinitions(uri.toString(), onCall) }
        assertEquals(listOf(DefinitionStatus.ERROR), events.map { it.status })
    }

    @Test
    fun `implementation fails when compilation fails`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } throws IllegalStateException("compile broke")
        assertInternalError("compile broke") { handler().getImplementations(uri.toString(), onCall) }
    }

    @Test
    fun `a request cancelled while resolving stays cancelled`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } coAnswers { awaitCancellation() }
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            handler(links = true).getDefinitions(uri.toString(), onCall)
        }
        request.cancel()
        assertFailsWith<CancellationException> { request.await() }
    }

    @Test
    fun `definition reports content modified when its origin is edited and the lookup then fails`(): Unit =
        runBlocking {
            editThenFail { fail -> every { compilation.getAst(uri) } answers { fail() } }
            assertContentModified { handler().getDefinitions(uri.toString(), onCall) }
        }

    @Test
    fun `definition links report content modified when their origin is edited and the lookup then fails`(): Unit =
        runBlocking {
            editThenFail { fail -> every { compilation.getAst(uri) } answers { fail() } }
            assertContentModified { handler(links = true).getDefinitions(uri.toString(), onCall) }
        }

    @Test
    fun `implementation reports content modified when its origin is edited and the lookup then fails`(): Unit =
        runBlocking {
            editThenFail { fail -> coEvery { compilation.ensureCompiled(uri) } coAnswers { fail() } }
            assertContentModified { handler().getImplementations(uri.toString(), onCall) }
        }

    @Test
    fun `references report content modified when their origin is edited and the lookup then fails`(): Unit =
        runBlocking {
            editThenFail { fail -> coEvery { compilation.ensureCompiled(uri) } coAnswers { fail() } }
            assertContentModified { handler().getReferences(uri.toString(), onCall, includeDeclaration = true) }
        }

    @Test
    fun `a request cancelled after its origin was edited stays cancelled`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } coAnswers {
            documents.update(uri, text.replace("call()", "other()"), 2)
            throw CancellationException("cancelled after the edit")
        }
        assertFailsWith<CancellationException> { handler().getDefinitions(uri.toString(), onCall) }
    }

    @Test
    fun `a request cancelled while a strategy runs ends cancelled with one cancelled event`(): Unit = runBlocking {
        documents.update(uri, "import com.example.Missing\n", 2)
        lateinit var request: Deferred<*>
        every { compilation.findClasspathClass(any()) } answers {
            request.cancel()
            throw CancellationException("cancelled while the strategy ran")
        }
        request = async(start = CoroutineStart.LAZY) {
            handler(links = true).getDefinitions(uri.toString(), onMissingImport)
        }

        assertFailsWith<CancellationException> { request.await() }
        assertTrue(request.isCancelled)
        assertEquals(listOf(DefinitionStatus.CANCELLED), events.map { it.status })
    }

    @Test
    fun `a position that is not a symbol answers empty`(): Unit = runBlocking {
        assertTrue(handler().getDefinitions(uri.toString(), onLiteral).left.isEmpty())
        assertTrue(handler(links = true).getDefinitions(uri.toString(), onLiteral).isLeft)
        assertTrue(handler().getImplementations(uri.toString(), onLiteral).left.isEmpty())
    }

    @Test
    fun `an unopened file answers empty`(): Unit = runBlocking {
        val unopened = "file:///Unopened.groovy"
        assertTrue(handler().getDefinitions(unopened, onCall).left.isEmpty())
        assertTrue(handler(links = true).getDefinitions(unopened, onCall).left.isEmpty())
        assertTrue(handler().getImplementations(unopened, onCall).left.isEmpty())
    }

    @Test
    fun `a link request reports exactly one telemetry event when it finds a definition`(): Unit = runBlocking {
        val answer = handler(links = true).getDefinitions(uri.toString(), onCall)

        assertTrue(answer.right.isNotEmpty(), "control: the link path answers")
        assertEquals(listOf(DefinitionStatus.SUCCESS), events.map { it.status })
    }

    @Test
    fun `a link request that misses reports one event and resolves once`(): Unit = runBlocking {
        val answer = handler(links = true).getDefinitions(uri.toString(), onLiteral)

        assertTrue(answer.isLeft && answer.left.isEmpty())
        verify(exactly = 1) { compilation.getCompiledSnapshot(uri) }
        assertEquals(listOf(DefinitionStatus.NO_DEFINITION), events.map { it.status })
    }

    /** Installs a seam whose first call edits the origin buffer and then throws, as a failure the edit caused. */
    private fun editThenFail(install: (() -> Nothing) -> Unit) = install {
        documents.update(uri, text.replace("call()", "other()"), 2)
        error("lookup broke after the edit")
    }

    private suspend fun assertContentModified(request: suspend () -> Any) {
        val error = assertFailsWith<ResponseErrorException> { request() }
        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    private fun failResolutionAfterCompile() {
        every { compilation.getAst(uri) } throws IllegalStateException("ast unavailable")
    }

    private suspend fun assertInternalError(reason: String, request: suspend () -> Any) {
        val error = assertFailsWith<ResponseErrorException> { request() }
        assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
        assertTrue(reason in error.responseError.message, "message: ${error.responseError.message}")
        assertEquals(null, error.responseError.data, "no stack trace reaches the client")
    }

    private fun handler(links: Boolean = false) = CrossFileOperationsHandler(
        compilation,
        documents,
        mockk<SourceNavigator>(relaxed = true),
        { links },
        { event -> events += event as DefinitionTelemetryEvent },
    )
}
