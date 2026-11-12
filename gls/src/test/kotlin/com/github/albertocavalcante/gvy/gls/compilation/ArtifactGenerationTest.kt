// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A compile that has finished parsing but not yet published is held while its inputs are invalidated.
 * Released afterwards, it must not write its now-retired parse or symbols back (#1184).
 */
class ArtifactGenerationTest {
    @TempDir
    lateinit var tempDir: Path

    private val uri = URI.create("file:///Held.groovy")
    private val content = "class Held { def m() {} }"
    private val gate = Gate()
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { GatedSession(it, gate) },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        gate.open()
        scope.cancel()
    }

    @Test
    fun `a compile retired by uri invalidation repopulates neither cache nor symbols`(): Unit = runBlocking {
        assertRetiredCompileDoesNotPublish { service.invalidateCache(uri) }
    }

    @Test
    fun `a compile retired by a global clear repopulates neither cache nor symbols`(): Unit = runBlocking {
        assertRetiredCompileDoesNotPublish { service.clearCaches() }
    }

    @Test
    fun `a compile retired by a worker change repopulates neither cache nor symbols`(): Unit = runBlocking {
        assertRetiredCompileDoesNotPublish { assertTrue(service.updateSelectedWorker(worker("other"))) }
    }

    @Test
    fun `a compile retired by a workspace change and its reversal does not publish`(): Unit = runBlocking {
        val root = Files.createDirectories(tempDir.resolve("ws"))
        val dependency = Files.createDirectories(tempDir.resolve("lib"))
        service.updateWorkspaceModel(root, emptyList(), emptyList())

        // v1 -> v2 -> v1: the fingerprint matches the one the compile started with again.
        assertRetiredCompileDoesNotPublish {
            service.updateWorkspaceModel(root, listOf(dependency), emptyList())
            service.updateWorkspaceModel(root, emptyList(), emptyList())
        }
    }

    @Test
    fun `a retired compile cannot replace the current result of a later compile`(): Unit = runBlocking {
        val held = holdCompile()
        service.invalidateCache(uri)
        gate.disarm()
        service.compile(uri, content)
        val current = assertNotNull(service.getCompiledSnapshot(uri)).parseResult
        val currentSymbols = assertNotNull(service.getSymbolStorage(uri))

        gate.open()
        held.await()

        assertSame(current, service.getCompiledSnapshot(uri)?.parseResult, "retired A replaced current B")
        assertSame(currentSymbols, service.getSymbolStorage(uri), "retired A replaced current B symbols")
    }

    @Test
    fun `invalidation cancels pending compilation jobs`(): Unit = runBlocking {
        val pending = service.compileAsync(scope, uri, content)
        assertTrue(gate.awaitEntered(), "compile never reached the parser")

        service.invalidateCache(uri)

        assertTrue(pending.isCancelled, "an invalidated job must not keep running toward publication")
        gate.open()
        assertFailsWith<CancellationException> { pending.await() }
        assertNull(service.getCompiledSnapshot(uri))
    }

    @Test
    fun `clearing caches cancels pending compilation jobs`(): Unit = runBlocking {
        val pending = service.compileAsync(scope, uri, content)
        assertTrue(gate.awaitEntered(), "compile never reached the parser")

        service.clearCaches()

        assertTrue(pending.isCancelled)
    }

    @Test
    fun `a recovery re-parse retired by invalidation repopulates nothing`(): Unit = runBlocking {
        // An unpackaged script named like its file is what the accessor re-parses at CONVERSION.
        val script = tempDir.resolve("Held.groovy").also { Files.writeString(it, "println 'held'") }.toUri()
        gate.disarm()
        service.compile(script, "println 'held'")
        assertNotNull(service.getCompiledSnapshot(script))
        gate.arm()

        val held = scope.async { service.getValidParseResult(script) }
        assertTrue(gate.awaitEntered(), "accessor never re-parsed")
        service.invalidateCache(script)
        gate.open()
        held.await()

        assertNull(service.getCompiledSnapshot(script), "retired re-parse repopulated the compilation cache")
        assertNull(service.getSymbolStorage(script), "retired re-parse repopulated the symbol index")
    }

    @Test
    fun `a delayed close cleanup cannot remove the facts of a reopened untitled buffer`(): Unit = runBlocking {
        assertCloseCleanupKeepsReopenedFacts(reopenedText = "class Reopened {}", expected = "Reopened")
    }

    @Test
    fun `a delayed close cleanup cannot remove a reopened buffer with identical text`(): Unit = runBlocking {
        assertCloseCleanupKeepsReopenedFacts(reopenedText = "class Original {}", expected = "Original")
    }

    private suspend fun assertCloseCleanupKeepsReopenedFacts(reopenedText: String, expected: String) {
        val untitled = URI.create("untitled:Scratch")
        gate.disarm()
        service.onDocumentOpened(untitled)
        documents.put(untitled, "class Original {}", 1)
        service.compile(untitled, "class Original {}")

        documents.remove(untitled)
        val closed = assertNotNull(service.onClosedSourceChanged(untitled), "a closed buffer retires its parse")

        service.onDocumentOpened(untitled)
        documents.put(untitled, reopenedText, 1)
        service.compile(untitled, reopenedText)
        // The cleanup scheduled by the close runs only now.
        service.symbolIndexingService.refreshAfterClose(untitled, closed)

        val index = assertNotNull(service.getSymbolStorage(untitled), "old close removed the reopened index")
        assertTrue(expected in index.getSymbols(untitled).map { it.name })
        assertNotNull(service.getCompiledSnapshot(untitled))
    }

    @Test
    fun `closing an open buffer again is refused while it is open`() {
        documents.put(uri, content, 1)
        assertNull(service.onClosedSourceChanged(uri), "an open buffer keeps its parse")
    }

    /** Holds A after parsing, applies [retire], releases A, then checks both artifacts and recovery. */
    private suspend fun assertRetiredCompileDoesNotPublish(retire: () -> Unit) {
        val held = holdCompile()

        retire()
        gate.open()
        held.await()

        assertNull(service.getCompiledSnapshot(uri), "retired compile repopulated the compilation cache")
        assertNull(service.getAst(uri), "retired compile is visible to content-less readers")
        assertNull(service.getSymbolStorage(uri), "retired compile repopulated the symbol index")

        // Invalidation must not suppress later publication: the next compile is current.
        service.compile(uri, content)
        assertNotNull(service.getCompiledSnapshot(uri))
        assertNotNull(service.getSymbolStorage(uri))
    }

    private fun holdCompile(): Deferred<CompilationResult> {
        val held = scope.async { service.compile(uri, content) }
        assertTrue(gate.awaitEntered(), "compile never reached the parser")
        return held
    }

    private fun worker(id: String) = WorkerDescriptor(
        id = id,
        supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("5.0.0")),
        capabilities = WorkerCapabilities(),
        connector = WorkerConnector.InProcess,
    )

    /** Holds the first parse that reaches it after the parser returned, until [open]. */
    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val armed = AtomicBoolean(true)

        fun arm() = armed.set(true)

        fun hold() {
            if (!armed.compareAndSet(true, false)) return
            entered.countDown()
            check(released.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "gate never opened" }
        }

        fun awaitEntered(): Boolean = entered.await(WAIT_SECONDS, TimeUnit.SECONDS)

        fun disarm() = armed.set(false)

        fun open() = released.countDown()
    }

    private class GatedSession(private val delegate: WorkerSession, private val gate: Gate) : WorkerSession {
        override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also { gate.hold() }
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
