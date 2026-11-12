// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.diagnostics.DiagnosticsOrchestrator
import com.github.albertocavalcante.gvy.gls.providers.crossfile.CrossFileOperationsHandler
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Navigation needs the open documents' compilation facts, not their whole diagnostics jobs: a slow linter that
 * runs after compilation must not delay a definition request whose facts are ready (#1187). Drives the real
 * diagnostics pipeline with a provider that never finishes.
 */
class CompilationReadinessTest {
    private val first = URI.create("file:///First.groovy")
    private val second = URI.create("file:///Second.groovy")
    private val firstText = "class First { void go() {} }"
    private val secondText = "class Second { void use(First f) { f.go() } }"

    private val held = AtomicBoolean(false)
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val parses = ConcurrentHashMap<String, AtomicInteger>()
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also {
                    parses.computeIfAbsent(request.content) { AtomicInteger() }.incrementAndGet()
                    if (held.compareAndSet(true, false)) {
                        entered.countDown()
                        released.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val linting = CountDownLatch(2)
    private val slowLinter = object : StreamingDiagnosticProvider {
        override val id = "slow-linter"
        override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
            linting.countDown()
            awaitCancellation()
        }
    }
    private val client = mockk<LanguageClient>(relaxed = true)
    private val diagnostics = DiagnosticsOrchestrator(
        scope,
        compilation,
        DiagnosticsService(listOf(slowLinter)),
        documents,
        ServerConfiguration(),
        client = { client },
        debounceMillis = 0,
    )
    private val handler =
        CrossFileOperationsHandler(compilation, documents, mockk<SourceNavigator>(relaxed = true), { false }, {})

    @AfterEach
    fun tearDown() {
        released.countDown()
        scope.cancel()
    }

    @Test
    fun `definition does not wait for a linter that never finishes`(): Unit = runBlocking {
        open(first, firstText)
        open(second, secondText)
        assertTrue(linting.await(WAIT_SECONDS, TimeUnit.SECONDS), "both documents compiled and reached the linter")

        // The old readiness joined each diagnostics job for up to 10 s; only compilation is awaited now.
        val definitions = withTimeout(READY_WITHIN_MS) { handler.getDefinitions(second.toString(), Position(0, 38)) }

        assertTrue(definitions.left.any { it.uri == first.toString() }, "cross-file definition: ${definitions.left}")
    }

    @Test
    fun `an edited document is compiled again instead of navigating its old index`(): Unit = runBlocking {
        open(first, "class Before {}")
        assertTrue(waitFor { compilation.getSymbolStorage(first) != null })
        documents.update(first, "class After {}", 2)

        withTimeout(READY_WITHIN_MS) { handler.getDefinitions(first.toString(), Position(0, 7)) }

        val names = compilation.getSymbolStorage(first)?.getSymbols(first)?.map { it.name }.orEmpty()
        assertTrue("After" in names && "Before" !in names, "navigation used the old buffer: $names")
    }

    @Test
    fun `cancelling one request does not retire the compile another request shares`(): Unit = runBlocking {
        documents.put(first, firstText, 1) // opened without diagnostics: the requests start the compile
        held.set(true)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            handler.getDefinitions(first.toString(), Position(0, 21))
        }
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first request never compiled")
        // Joined before the cancel: a genuine co-waiter of the compile the first request started.
        val surviving = async(start = CoroutineStart.UNDISPATCHED) {
            handler.getDefinitions(first.toString(), Position(0, 21))
        }

        cancelled.cancel() // e.g. a superseded Ctrl+hover
        released.countDown()

        assertTrue(surviving.await().left.isNotEmpty(), "the co-waiter lost its answer")
        assertEquals(1, parses[firstText]?.get(), "the shared compile was cancelled and redone")
    }

    private fun open(uri: URI, text: String) {
        documents.put(uri, text, 1)
        diagnostics.trigger(uri, text)
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(WAIT_SECONDS.toInt() * 20) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val READY_WITHIN_MS = 5_000L
    }
}
