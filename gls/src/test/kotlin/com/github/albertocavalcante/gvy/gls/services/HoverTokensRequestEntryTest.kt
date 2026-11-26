// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HoverTokensRequestEntryTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `hover copies URI and position before queued execution`() {
        Fixture().use { fixture ->
            val expected = fixture.service.hover(fixture.hoverParams()).get(10, TimeUnit.SECONDS)
            assertContains(expected.contents.right.value, "String value()")
            fixture.block()
            val params = fixture.hoverParams()
            val response = fixture.service.hover(params)
            params.textDocument.uri = "file:///Different.groovy"
            params.position.character = 0
            fixture.release.countDown()
            assertEquals(expected, response.get(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `tokens reject equal text reopen before queued execution`() {
        Fixture().use { fixture ->
            fixture.block()
            val response = fixture.service.semanticTokensFull(
                SemanticTokensParams(TextDocumentIdentifier(fixture.uri.toString())),
            )
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.compilation.onDocumentOpened(fixture.uri)
            fixture.documents.put(fixture.uri, SOURCE, VERSION)
            fixture.release.countDown()
            val error = assertFailsWith<ExecutionException> { response.get(10, TimeUnit.SECONDS) }
            val responseError = assertIs<ResponseErrorException>(error.cause)
            assertEquals(ResponseErrorCode.ContentModified.value, responseError.responseError.code)
        }
    }

    private inner class Fixture : AutoCloseable {
        val uri = workspace.resolve("Example.groovy").toUri()
        val documents = DocumentProvider().apply { put(uri, SOURCE, VERSION) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher = executor.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        val release = CountDownLatch(1)

        init {
            compilation.workspaceManager.initializeWorkspace(workspace)
            compilation.onDocumentOpened(uri)
        }

        fun hoverParams() = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, 23))

        fun block() {
            val entered = CountDownLatch(1)
            executor.execute {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            dispatcher.close()
            compilation.shutdown()
        }
    }

    private companion object {
        const val SOURCE = "class Example { String value() { 'hello' } }\n"
        val VERSION = DocumentVersion.of(7)
    }
}
