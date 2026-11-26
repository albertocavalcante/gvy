// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.gvy.testing.spock.SpockTestDetector
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestEntryAcquisitionTest {
    enum class FactRoute { LENS, SYMBOL }
    enum class RangeRoute { FOLDING, SELECTION }

    @ParameterizedTest
    @EnumSource(FactRoute::class)
    fun `a closed request cannot adopt a buffer opened while queued`(route: FactRoute) {
        Fixture().use { fixture ->
            fixture.blockDispatcher()
            val identifier = TextDocumentIdentifier(fixture.uri.toString())
            val answer = when (route) {
                FactRoute.LENS -> fixture.service.codeLens(CodeLensParams(identifier))
                FactRoute.SYMBOL -> fixture.service.documentSymbol(DocumentSymbolParams(identifier))
            }
            fixture.open()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(RangeRoute::class)
    fun `an equal text reopen while queued cannot answer an older request`(route: RangeRoute) {
        Fixture().use { fixture ->
            fixture.open()
            fixture.blockDispatcher()
            val answer = fixture.rangeRequest(route)
            fixture.documents.remove(fixture.uri)
            fixture.compilation.onClosedSourceChanged(fixture.uri)
            fixture.open()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(RangeRoute::class)
    fun `mutating request parameters while queued does not change the answer`(route: RangeRoute) {
        Fixture().use { fixture ->
            fixture.open()
            val expected = fixture.rangeRequest(route).get(10, TimeUnit.SECONDS)
            fixture.blockDispatcher()
            val identifier = TextDocumentIdentifier(fixture.uri.toString())
            val position = Position(1, 8)
            val answer = when (route) {
                RangeRoute.FOLDING -> fixture.service.foldingRange(FoldingRangeRequestParams(identifier))
                RangeRoute.SELECTION -> fixture.service.selectionRange(
                    SelectionRangeParams(identifier, listOf(position)),
                )
            }
            identifier.uri = "file:///Different.groovy"
            position.line = -1
            fixture.release.countDown()
            assertEquals(expected, answer.get(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @EnumSource(RangeRoute::class)
    fun `separate range authorities cannot miss an endpoint only open`(route: RangeRoute) {
        Fixture(separateAuthorities = true).use { fixture ->
            fixture.blockDispatcher()
            val answer = fixture.rangeRequest(route)
            fixture.documents.put(fixture.uri, "class Endpoint {}", DocumentVersion.of(7))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @Test
    fun `an empty selection request still validates its captured source`() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.blockDispatcher()
            val answer = fixture.service.selectionRange(
                SelectionRangeParams(TextDocumentIdentifier(fixture.uri.toString()), emptyList()),
            )
            fixture.documents.put(fixture.uri, "class Changed {}", DocumentVersion.of(8))
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(FactRoute::class)
    fun `malformed uri completes the future exceptionally`(route: FactRoute) {
        Fixture().use { fixture ->
            val invalid = TextDocumentIdentifier("::invalid")
            val malformed = when (route) {
                FactRoute.LENS -> fixture.service.codeLens(CodeLensParams(invalid))
                FactRoute.SYMBOL -> fixture.service.documentSymbol(DocumentSymbolParams(invalid))
            }
            assertThrows<ExecutionException> { malformed.get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest
    @EnumSource(FactRoute::class)
    fun `missing document completes the future exceptionally`(route: FactRoute) {
        Fixture().use { fixture ->
            val missing = when (route) {
                FactRoute.LENS -> fixture.service.codeLens(CodeLensParams())
                FactRoute.SYMBOL -> fixture.service.documentSymbol(DocumentSymbolParams())
            }
            assertThrows<ExecutionException> { missing.get(10, TimeUnit.SECONDS) }
        }
    }

    @ParameterizedTest
    @EnumSource(FactRoute::class)
    fun `separate closed authorities cannot adopt an endpoint buffer`(route: FactRoute) {
        Fixture(separateAuthorities = true).use { fixture ->
            fixture.blockDispatcher()
            val identifier = TextDocumentIdentifier(fixture.uri.toString())
            val answer = when (route) {
                FactRoute.LENS -> fixture.service.codeLens(CodeLensParams(identifier))
                FactRoute.SYMBOL -> fixture.service.documentSymbol(DocumentSymbolParams(identifier))
            }
            fixture.open()
            fixture.release.countDown()
            assertModified(answer)
        }
    }

    @ParameterizedTest
    @EnumSource(FactRoute::class)
    fun `endpoint open during a separate authority disk parse cannot return the old disk answer`(
        route: FactRoute,
        @TempDir directory: Path,
    ) {
        val uri = Files.writeString(directory.resolve("Disk.groovy"), "class Disk {}\n").toUri()
        val documents = DocumentProvider()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val compilation = GroovyCompilationService(
            documentProvider = DocumentProvider(),
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val result = delegate.parse(request)
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return result
                    }
                }
            },
        )
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val identifier = TextDocumentIdentifier(uri.toString())
            val answer = when (route) {
                FactRoute.LENS -> service.codeLens(CodeLensParams(identifier))
                FactRoute.SYMBOL -> service.documentSymbol(DocumentSymbolParams(identifier))
            }
            if (entered.await(3, TimeUnit.SECONDS)) {
                documents.put(uri, "class Buffer {}\n", DocumentVersion.of(7))
                release.countDown()
            }
            assertModified(answer)
        } finally {
            release.countDown()
            scope.cancel()
            dispatcher.close()
            compilation.shutdown()
        }
    }

    @Test
    fun `detectors are captured before a queued request`() {
        val registry = TestFrameworkRegistry.default
        val previous = registry.getDetectors()
        try {
            Fixture().use { fixture ->
                fixture.open()
                fixture.blockDispatcher()
                val answer = fixture.service.codeLens(CodeLensParams(TextDocumentIdentifier(fixture.uri.toString())))
                registry.register(SpockTestDetector())
                fixture.release.countDown()
                assertModified(answer)
            }
        } finally {
            registry.clear()
            previous.forEach(registry::register)
        }
    }

    private fun assertModified(answer: CompletableFuture<*>) {
        val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    private class Fixture(separateAuthorities: Boolean = false) : AutoCloseable {
        val uri = URI.create("file:///EntryAcquisition.groovy")
        private val source = "class Sample {\n void run() {\n  println 1\n }\n}\n"
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(
            documentProvider = if (separateAuthorities) DocumentProvider() else documents,
        )
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher = executor.asCoroutineDispatcher()
        private val scope = CoroutineScope(dispatcher + SupervisorJob())
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        val release = CountDownLatch(1)

        fun open() {
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
        }

        fun blockDispatcher() {
            val entered = CountDownLatch(1)
            executor.execute {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
        }

        fun rangeRequest(route: RangeRoute): CompletableFuture<*> {
            val identifier = TextDocumentIdentifier(uri.toString())
            return when (route) {
                RangeRoute.FOLDING -> service.foldingRange(FoldingRangeRequestParams(identifier))
                RangeRoute.SELECTION -> service.selectionRange(
                    SelectionRangeParams(identifier, listOf(Position(1, 8))),
                )
            }
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            dispatcher.close()
            compilation.shutdown()
        }
    }
}
