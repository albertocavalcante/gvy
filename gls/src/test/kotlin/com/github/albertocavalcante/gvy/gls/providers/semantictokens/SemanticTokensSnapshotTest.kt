// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
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
import kotlin.test.assertTrue

class SemanticTokensSnapshotTest {
    private val uri = URI.create("file:///SemanticSnapshot.groovy")
    private val foreign = URI.create("file:///ForeignTokens.groovy")
    private val source = "class Fresh {}\n"
    private val documents = DocumentProvider()
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
    fun `cold token queries highlight current source without publishing`() {
        documents.put(uri, source, 1)
        assertFreshClass(request())
        assertNull(compilation.getCompiledSnapshot(uri))
        assertTrue(compilation.getWorkspaceSymbolIndex()?.implementationSnapshot?.containsKey(uri) != true)
    }

    @Test
    fun `older compiled text cannot supply tokens for the current buffer`(): Unit = runBlocking {
        val old = "class Old {}\n"
        actual.compile(uri, old)
        documents.put(uri, source, 2)
        assertFreshClass(request())
        assertEquals(old, assertNotNull(compilation.getCompiledSnapshot(uri)).sourceText)
    }

    @Test
    fun `closed or unknown documents cannot borrow tokens from an old cache`(): Unit = runBlocking {
        actual.compile(uri, source)
        assertTrue(request().data.isEmpty())
    }

    @Test
    fun `cancellation is not converted into an empty token success`() {
        documents.put(uri, source, 1)
        coEvery { compilation.ensureCompiled(uri) } throws CancellationException("cancelled")
        coEvery { compilation.compileTransient(uri, source, any()) } throws CancellationException("cancelled")
        assertThrows<CancellationException> { request() }
    }

    @Test
    fun `a version change during AST acquisition rejects the response`(): Unit = runBlocking {
        documents.put(uri, source, 1)
        actual.compile(uri, source)
        val cached = assertNotNull(compilation.getCompiledSnapshot(uri))
        every { compilation.getAstModel(uri) } answers {
            documents.put(uri, source, 2)
            cached.parseResult.astModel
        }
        every { compilation.getCompiledSnapshot(uri) } answers {
            documents.put(uri, source, 2)
            cached
        }
        val error = assertThrows<ExecutionException> { request() }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    @Test
    fun `Groovy tokens are restricted to the requested model URI`(): Unit = runBlocking {
        val text = "class Hidden {}\n"
        actual.compile(foreign, text)
        val model = assertNotNull(compilation.getCompiledSnapshot(foreign)).parseResult.astModel
        assertTrue(GroovySemanticTokenProvider.getSemanticTokens(model, foreign, sourceText = text).isNotEmpty())
        assertTrue(
            GroovySemanticTokenProvider.getSemanticTokens(model, uri, sourceText = "// no declarations\n").isEmpty(),
        )
    }

    @Test
    fun `Jenkins tokens are restricted to the requested model URI`(): Unit = runBlocking {
        actual.compile(foreign, "pipeline { stage('Build') {} }\n")
        val model = assertNotNull(compilation.getCompiledSnapshot(foreign)).parseResult.astModel
        assertTrue(JenkinsSemanticTokenProvider.getSemanticTokens(model, foreign, true).isNotEmpty())
        assertTrue(JenkinsSemanticTokenProvider.getSemanticTokens(model, uri, true).isEmpty())
    }

    @Test
    fun `matching current source still produces ordinary class tokens`(): Unit = runBlocking {
        documents.put(uri, source, 1)
        actual.compile(uri, source)
        assertFreshClass(request())
    }

    @Test
    fun `unknown uncached documents return an empty token list`() {
        assertTrue(request().data.isEmpty())
        assertNull(compilation.getCompiledSnapshot(uri))
    }

    private fun request(): SemanticTokens = service.semanticTokensFull(
        SemanticTokensParams().apply { textDocument = TextDocumentIdentifier(uri.toString()) },
    ).get(5, TimeUnit.SECONDS)

    private fun assertFreshClass(result: SemanticTokens) {
        assertEquals(0, result.data.size % 5)
        assertTrue(
            result.data.chunked(5).any { token ->
                token[2] == "Fresh".length && token[3] == GroovySemanticTokenProvider.TokenTypes.CLASS
            },
            "Class metadata must come from Fresh, not the older declaration",
        )
    }
}
