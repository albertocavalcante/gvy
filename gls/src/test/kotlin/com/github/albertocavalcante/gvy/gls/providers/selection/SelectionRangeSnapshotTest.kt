// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SelectionRangeSnapshotTest {
    private val uri = URI.create("file:///SelectionSnapshot.groovy")
    private val source = "def value = 1\n"
    private val documents = DocumentProvider()
    private var afterParse: () -> Unit = {}
    private var beforeParse: () -> Unit = {}
    private var onNodes: () -> Unit = {}
    private var parseCount = 0
    private val compilation = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    parseCount++
                    beforeParse()
                    val parsed = delegate.parse(request)
                    afterParse()
                    val model = parsed.astModel
                    return parsed.copy(
                        astModel = object : GroovyAstModel by model {
                            override fun getNodes(uri: URI): List<ASTNode> {
                                onNodes()
                                return model.getNodes(uri)
                            }
                        },
                    )
                }
            }
        },
    )
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val service = GroovyTextDocumentService(
        coroutineScope = scope,
        compilationService = compilation,
        options = GroovyTextDocumentServiceOptions(documentProvider = documents),
    )

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.shutdown()
    }

    @Test
    fun `version changes during compilation reject the selection`(): Unit = runBlocking {
        documents.put(uri, source, DocumentVersion.of(7))
        afterParse = { documents.put(uri, source, DocumentVersion.of(8)) }
        assertEquals(ResponseErrorCode.ContentModified.value, failure().responseError.code)
    }

    @Test
    fun `version changes after snapshot acquisition reject the selection`(): Unit = runBlocking {
        documents.put(uri, source, DocumentVersion.of(7))
        onNodes = { documents.put(uri, source, DocumentVersion.of(8)) }
        assertEquals(ResponseErrorCode.ContentModified.value, failure().responseError.code)
    }

    @Test
    fun `a cached AST for older text cannot contribute ranges`(): Unit = runBlocking {
        prepare()
        documents.put(uri, "// fresh\n", DocumentVersion.of(8))
        val selected = assertNotNull(service.selectionRange(params()).get(5, TimeUnit.SECONDS)).single()
        assertEquals(Range(Position(0, 3), Position(0, 8)), selected.range)
        assertEquals(Range(Position(0, 0), Position(0, 8)), selected.parent.range)
        assertEquals(Range(Position(0, 0), Position(1, 0)), selected.parent.parent.range)
        assertNull(selected.parent.parent.parent)
    }

    @Test
    fun `compilation cancellation propagates`(): Unit = runBlocking {
        documents.put(uri, source, DocumentVersion.of(7))
        beforeParse = { throw CancellationException("cancelled") }
        assertThrows<CancellationException> { service.selectionRange(params()).get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `missing documents return null without compilation`() {
        assertNull(service.selectionRange(params()).get(5, TimeUnit.SECONDS))
        assertEquals(0, parseCount)
    }

    @Test
    fun `request positions are captured before suspension`(): Unit = runBlocking {
        documents.put(uri, source, DocumentVersion.of(7))
        val request = params()
        afterParse = { request.positions.single().line = 1000 }
        val selected = assertNotNull(service.selectionRange(request).get(5, TimeUnit.SECONDS)).single()
        assertEquals(1000, request.positions.single().line)
        assertEquals(Range(Position(0, 4), Position(0, 9)), selected.range)
    }

    private suspend fun prepare(): CompilationResult {
        documents.put(uri, source, DocumentVersion.of(7))
        return compilation.compile(uri, source)
    }

    private fun params() = SelectionRangeParams().apply {
        textDocument = TextDocumentIdentifier(uri.toString())
        positions = listOf(Position(0, 5))
    }

    private fun failure(): ResponseErrorException {
        val exception = assertThrows<ExecutionException> {
            service.selectionRange(params()).get(5, TimeUnit.SECONDS)
        }
        return assertIs<ResponseErrorException>(exception.cause)
    }
}
