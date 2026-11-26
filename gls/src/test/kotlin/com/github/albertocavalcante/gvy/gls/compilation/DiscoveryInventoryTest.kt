// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.testing.DiscoverTestsParams
import com.github.albertocavalcante.gvy.gls.providers.testing.TestRequestDelegate
import com.github.albertocavalcante.gvy.gls.providers.testing.TestSuite
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiscoveryInventoryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a completed suite cannot go stale while discovering its peer`(
        beforePeerCapture: Boolean,
        @TempDir root: Path,
    ) {
        val firstText = "class Alpha { @org.junit.jupiter.api.Test void original() {} }"
        val latestText = firstText.replace("original", "updated")
        val first = Files.writeString(root.resolve("Alpha.groovy"), firstText).toUri()
        val secondText = "class Beta { @org.junit.jupiter.api.Test void stable() {} }"
        val second = Files.writeString(root.resolve("Beta.groovy"), secondText).toUri()
        val documents = spyk(DocumentProvider()).apply { put(first, firstText, DocumentVersion.of(1)) }
        val manager = spyk(WorkspaceManager())
        every { manager.getWorkspaceSourceUris() } returns listOf(first, second)
        val held = HeldPeer(second)
        val compilation = GroovyCompilationService(
            DiscoveryInventoryTest::class.java.classLoader,
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = held::decorate,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val diagnostics = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        val registry = TestFrameworkRegistry.default
        val previous = registry.getDetectors()
        try {
            val extracted = observeFirstSuite(registry)
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.onDocumentOpened(first)
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(diagnostics, compilation, options)
            val edit = DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(first.toString(), 2),
                listOf(TextDocumentContentChangeEvent(latestText)),
            )
            val pendingEdit = AtomicBoolean(beforePeerCapture)
            every { documents.getBuffer(second) } answers {
                if (pendingEdit.compareAndSet(true, false)) {
                    check(extracted.count == 0L)
                    service.didChange(edit)
                }
                callOriginal()
            }
            val delegate = TestRequestDelegate(scope, compilation, buildToolManagerProvider = { null })
            val request = delegate.discoverTests(DiscoverTestsParams(root.toUri().toString()))
            assertTrue(held.entered.await(10, TimeUnit.SECONDS))
            assertEquals(0L, extracted.count, "Alpha's old test must already have been extracted")
            if (!beforePeerCapture) service.didChange(edit)
            assertEquals(latestText, documents.get(first))
            held.release.countDown()
            assertCurrentInventory(request)
        } finally {
            held.release.countDown()
            diagnostics.cancel()
            scope.cancel()
            compilation.shutdown()
            registry.clear()
            previous.forEach(registry::register)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["current-io", "stale-io", "stale-cancel", "stale-fatal"])
    fun `peer failure is delivered under the original inventory window`(scenario: String, @TempDir root: Path) {
        val original = "class Alpha { @org.junit.jupiter.api.Test void original() {} }"
        val updated = original.replace("original", "updated")
        val first = Files.writeString(root.resolve("Alpha.groovy"), original).toUri()
        val second = Files.writeString(root.resolve("Beta.groovy"), "class Beta {}").toUri()
        val peer = FailingPeer(first, second, scenario)
        val documents = spyk(DocumentProvider()).apply { put(first, original, DocumentVersion.of(1)) }
        val manager = spyk(WorkspaceManager())
        every { manager.getWorkspaceSourceUris() } returns listOf(first, second)
        val compilation = GroovyCompilationService(
            DiscoveryInventoryTest::class.java.classLoader,
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = peer::decorate,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val diagnostics = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        val registry = TestFrameworkRegistry.default
        val previous = registry.getDetectors()
        try {
            val extracted = observeFirstSuite(registry)
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.onDocumentOpened(first)
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(diagnostics, compilation, options)
            val pendingEdit = AtomicBoolean(scenario != "current-io")
            every { documents.getBuffer(second) } answers {
                if (pendingEdit.compareAndSet(true, false)) {
                    check(extracted.count == 0L)
                    service.didChange(
                        DidChangeTextDocumentParams(
                            VersionedTextDocumentIdentifier(first.toString(), 2),
                            listOf(TextDocumentContentChangeEvent(updated)),
                        ),
                    )
                }
                callOriginal()
            }
            val delegate = TestRequestDelegate(scope, compilation, buildToolManagerProvider = { null })
            val request = delegate.discoverTests(DiscoverTestsParams(root.toUri().toString()))
            val failed = assertFails { request.get(10, TimeUnit.SECONDS) }
            val cause = (failed as? ExecutionException)?.cause ?: failed
            assertEquals(0L, extracted.count)
            assertEquals(if (scenario == "current-io") original else updated, peer.overlay)
            if (scenario == "stale-io") {
                val response = assertIs<ResponseErrorException>(cause).responseError
                assertEquals(ResponseErrorCode.ContentModified.value, response.code)
            } else {
                assertSame(peer.failure, cause)
            }
        } finally {
            diagnostics.cancel()
            scope.cancel()
            compilation.shutdown()
            registry.clear()
            previous.forEach(registry::register)
        }
    }

    private class FailingPeer(private val first: URI, private val second: URI, scenario: String) {
        val failure = when (scenario) {
            "stale-cancel" -> CancelledParse(second)
            "stale-fatal" -> FatalParse(second)
            else -> FileSystemException("beta-compiler.jar", null, "Beta compiler input unavailable")
        }

        @Volatile
        var overlay: String? = null

        fun decorate(worker: WorkerSession) = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                if (request.uri == second) {
                    overlay = request.openSources[first]
                    throw failure
                }
                return worker.parse(request)
            }
        }
    }

    private class CancelledParse(val input: URI) : CancellationException("Cancelled parse of $input")
    private class FatalParse(val input: URI) : AssertionError("Fatal parse of $input")

    private fun assertCurrentInventory(request: CompletableFuture<List<TestSuite>>) {
        try {
            val suites = request.get(10, TimeUnit.SECONDS).associate { suite ->
                suite.suite to suite.tests.map { it.test }
            }
            assertEquals(mapOf("Alpha" to listOf("updated"), "Beta" to listOf("stable")), suites)
        } catch (failed: ExecutionException) {
            val response = assertIs<ResponseErrorException>(failed.cause).responseError
            assertEquals(ResponseErrorCode.ContentModified.value, response.code)
        }
    }

    private fun observeFirstSuite(registry: TestFrameworkRegistry): CountDownLatch {
        val extracted = CountDownLatch(1)
        val detector = spyk(JUnit5TestDetector())
        every { detector.extractTests(match { it.name == "Alpha" }) } answers {
            callOriginal().also { items -> if (items.any { it.name == "original" }) extracted.countDown() }
        }
        registry.clear()
        registry.register(detector)
        return extracted
    }

    private class HeldPeer(private val uri: URI) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun decorate(worker: WorkerSession) = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                if (request.uri == uri) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
                return worker.parse(request)
            }
        }
    }
}
