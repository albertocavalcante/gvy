// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextHandlerBoundaryTest {
    enum class Route { TYPE_DEFINITION, INLAY_HINT }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun `queued request cannot borrow identical reopened source or mutated parameters`(route: Route) {
        val uri = URI.create("file:///QueuedContext.groovy")
        val source = "class Foo {}\nFoo f = new Foo(); println f\n"
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(7)) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    block.run()
                }
            }
        }
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            compilation.onDocumentOpened(uri)
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val document = TextDocumentIdentifier(uri.toString())
            val position = Position(1, 25)
            val range = Range(Position(0, 0), Position(2, 0))
            val answer = when (route) {
                Route.TYPE_DEFINITION -> service.typeDefinition(TypeDefinitionParams(document, position))
                Route.INLAY_HINT -> service.inlayHint(InlayHintParams(document, range))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request was not queued")
            documents.remove(uri)
            compilation.onClosedSourceChanged(uri)
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
            document.uri = "file:///Mutated.groovy"
            position.line = 99
            range.start.line = 99
            release.countDown()
            val failure = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(failure.cause).responseError.code,
            )
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
