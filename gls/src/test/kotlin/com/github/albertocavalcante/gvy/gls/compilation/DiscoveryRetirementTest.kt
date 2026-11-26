// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.testing.DiscoverTestsParams
import com.github.albertocavalcante.gvy.gls.providers.testing.TestRequestDelegate
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiscoveryRetirementTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `retired recovery cannot clear an existing test suite`(openPeer: Boolean, @TempDir root: Path) {
        val source = "class Suite { @org.junit.jupiter.api.Test void knownTest() {} }"
        val uri = Files.writeString(root.resolve("Subject.groovy"), source).toUri()
        val otherSource = "class Peer {}"
        val other = Files.writeString(root.resolve("Peer.groovy"), otherSource).toUri()
        val hold = HeldConversion(uri)
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(
            DiscoveryRetirementTest::class.java.classLoader,
            documentProvider = documents,
            workerSessionDecorator = hold::decorate,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val diagnostics = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        val registry = TestFrameworkRegistry.default
        val previous = registry.getDetectors()
        try {
            registry.registerIfAbsent(JUnit5TestDetector())
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            val delegate = TestRequestDelegate(scope, compilation, buildToolManagerProvider = { null })
            val params = DiscoverTestsParams(root.toUri().toString())
            val expected = delegate.discoverTests(params).get(10, TimeUnit.SECONDS).single()
            assertEquals("Suite", expected.suite)
            assertEquals(listOf("knownTest"), expected.tests.map { it.test })

            compilation.compilationCacheService.invalidate(uri)
            hold.armed.set(true)
            val pending = delegate.discoverTests(params)
            assertTrue(hold.entered.await(10, TimeUnit.SECONDS), "The suite's structural recovery never started")
            if (openPeer) {
                val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
                GroovyTextDocumentService(diagnostics, compilation, options).didOpen(
                    DidOpenTextDocumentParams(TextDocumentItem(other.toString(), "groovy", 1, otherSource)),
                )
            } else {
                compilation.compilationCacheService.invalidate(uri)
            }
            hold.armed.set(false)
            hold.release.countDown()
            try {
                assertEquals(listOf(expected), pending.get(10, TimeUnit.SECONDS), "Retirement lost a known suite")
            } catch (failed: ExecutionException) {
                val response = assertIs<ResponseErrorException>(failed.cause).responseError
                assertEquals(ResponseErrorCode.ContentModified.value, response.code)
            }
            assertEquals(listOf(expected), delegate.discoverTests(params).get(10, TimeUnit.SECONDS))
        } finally {
            hold.release.countDown()
            diagnostics.cancel()
            scope.cancel()
            compilation.shutdown()
            registry.clear()
            previous.forEach(registry::register)
        }
    }

    private class HeldConversion(private val uri: URI) {
        val armed = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun decorate(worker: WorkerSession) = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                if (request.uri == uri && request.compilePhase == Phases.CANONICALIZATION) {
                    return worker.parse(request.copy(content = "def placeholder() {}"))
                }
                if (request.uri == uri && request.compilePhase == Phases.CONVERSION && armed.get()) {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
                return worker.parse(request)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a current file without tests returns a successful empty list`(noAst: Boolean, @TempDir root: Path) {
        Files.writeString(root.resolve("Ordinary.groovy"), "class Ordinary {}")
        val compilation = GroovyCompilationService(
            workerSessionDecorator = { worker ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = worker.parse(request)
                        return if (noAst) parsed.copy(ast = null) else parsed
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            val delegate = TestRequestDelegate(scope, compilation, buildToolManagerProvider = { null })
            val suites = delegate.discoverTests(DiscoverTestsParams(root.toUri().toString())).get(10, TimeUnit.SECONDS)
            assertTrue(suites.isEmpty())
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
