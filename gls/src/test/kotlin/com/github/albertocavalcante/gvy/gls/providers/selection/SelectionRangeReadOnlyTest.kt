package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionRangeReadOnlyTest {
    private val uri = URI.create("file:///SelectionReadOnly.groovy")
    private val source = "def result = value + 1\n"
    private val documents = DocumentProvider().apply { put(uri, source, 7) }
    private val actual = GroovyCompilationService()
    private val compilation = spyk(actual)
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    @AfterEach
    fun close() {
        scope.cancel()
        actual.clearCaches()
    }

    @Test
    fun `cold selection returns structure without publishing shared document state`() {
        val selected = assertNotNull(service.selectionRange(params()).get(5, TimeUnit.SECONDS)).single()
        assertTrue(Range(Position(0, 13), Position(0, 22)) in chain(selected))
        assertNull(compilation.getCompiledSnapshot(uri))
        assertFalsePublished()
    }

    @Test
    fun `an older cached source remains untouched while current structure is selected`(): Unit = runBlocking {
        val prior = "def prior = 2\n"
        actual.compile(uri, prior)
        val selected = assertNotNull(service.selectionRange(params()).get(5, TimeUnit.SECONDS)).single()
        assertTrue(Range(Position(0, 13), Position(0, 22)) in chain(selected))
        assertEquals(prior, assertNotNull(compilation.getCompiledSnapshot(uri)).sourceText)
    }

    @Test
    fun `a raced publishing waiter cannot leave a stale AST after the request`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } coAnswers {
            documents.put(uri, "def newer = 2\n", 8)
            actual.compile(uri, source)
            null
        }
        try {
            service.selectionRange(params()).get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(error.cause).responseError.code,
            )
        }
        val cached = compilation.getCompiledSnapshot(uri)
        assertTrue(cached == null || cached.sourceText == documents.get(uri), "No rejected query may publish old text")
    }

    @Test
    fun `a document change during transient parsing rejects without publishing`(): Unit = runBlocking {
        val parsed = actual.compileTransient(uri, source, Phases.CONVERSION)
        coEvery { compilation.compileTransient(uri, source, any()) } coAnswers {
            documents.put(uri, "def newer = 2\n", 8)
            parsed
        }
        val error = assertThrows<ExecutionException> { service.selectionRange(params()).get(5, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
        assertNull(compilation.getCompiledSnapshot(uri))
        assertFalsePublished()
    }

    private fun params() = SelectionRangeParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        positions = listOf(Position(0, 14))
    }

    private fun assertFalsePublished() {
        assertTrue(compilation.getWorkspaceSymbolIndex()?.implementationSnapshot?.containsKey(uri) != true)
    }

    private fun chain(first: SelectionRange): List<Range> = buildList {
        var current: SelectionRange? = first
        while (current != null) {
            assertTrue(size <= SelectionRangeBuilder.MAX_SELECTION_DEPTH)
            add(current.range)
            current = current.parent
        }
    }
}
