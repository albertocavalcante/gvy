package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.google.gson.Gson
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectionRangeRequestValidationTest {
    private val uri = URI.create("file:///SelectionValidation.groovy")
    private val documents = DocumentProvider().apply { put(uri, "x\r\n", 7) }
    private val compilation = spyk(GroovyCompilationService())
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val service = GroovyTextDocumentService(
        coroutineScope = scope,
        compilationService = compilation,
        options = GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.clearCaches()
    }

    @ParameterizedTest
    @CsvSource("-1,0", "0,-1", "2,0")
    fun `invalid UTF16 positions are rejected before compilation`(line: Int, character: Int) {
        invalid(params(listOf(Position(line, character))))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"textDocument":{"uri":"file:///SelectionValidation.groovy"}}""",
            """{"positions":[{"line":0,"character":0}]}""",
            """{"textDocument":{"uri":"file:///SelectionValidation.groovy"},"positions":[null]}""",
        ],
    )
    fun `missing required fields and null positions return invalid params`(json: String) {
        invalid(Gson().fromJson(json, SelectionRangeParams::class.java))
    }

    @Test
    fun `oversized batches are rejected instead of truncated`() {
        invalid(params(List(SelectionRangeProvider.MAX_SELECTION_POSITIONS + 1) { Position(0, 0) }))
    }

    @Test
    fun `character offsets beyond a line clamp to its end`() {
        val request = params(listOf(Position(0, Int.MAX_VALUE), Position(1, Int.MAX_VALUE)))
        val result = assertNotNull(service.selectionRange(request).get(5, TimeUnit.SECONDS))
        assertEquals(2, result.size)
        assertEquals(Range(Position(0, 1), Position(0, 1)), result[0].range)
        assertEquals(Range(Position(1, 0), Position(1, 0)), result[1].range)
    }

    @Test
    fun `empty batches return empty without compilation`() {
        val result = service.selectionRange(params(emptyList())).get(5, TimeUnit.SECONDS)
        assertTrue(result!!.isEmpty())
        coVerify(exactly = 0) { compilation.ensureCompiled(any()) }
    }

    @Test
    fun `malformed document URIs return invalid params`() {
        val request = params(listOf(Position(0, 0))).apply { textDocument = TextDocumentIdentifier("%%%bad") }
        invalid(request)
    }

    private fun params(values: List<Position>) = SelectionRangeParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        positions = values
    }

    private fun invalid(params: SelectionRangeParams) {
        val error = assertThrows<ExecutionException> { service.selectionRange(params).get(5, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.InvalidParams.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
        coVerify(exactly = 0) { compilation.ensureCompiled(any()) }
    }
}
