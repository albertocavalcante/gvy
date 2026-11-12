// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import io.mockk.clearMocks
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What one large watched-files notification costs on the LSP notification thread (#1428): every closed source
 * event is retired synchronously before [GroovyWorkspaceService.didChangeWatchedFiles] returns. Measured on CI
 * at 47-92 ms warm for 5,000 events, so the retirement stays per event. The 5,000 / 500 warm ratio catches a
 * regression to quadratic cost, and an absolute budget backs it up. The timings are printed (`[#1428]` lines) and
 * the retirement semantics asserted on every call.
 */
class WatchedFilesRetireCostTest {
    @TempDir
    lateinit var tempDir: Path

    // Background work (indexing launched by the drained queue) is parked, never run: only the notification counts.
    private val parked = ArrayDeque<Runnable>()
    private val scope = CoroutineScope(SupervisorJob() + Executor { parked.addLast(it) }.asCoroutineDispatcher())
    private val documents = DocumentProvider()

    // A plain instance, not a spy: a spy's interception would be part of the measured time.
    private val compilation =
        GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
    private val paused = ArrayDeque<Runnable>()
    private val workspace = GroovyWorkspaceService(
        compilation,
        compilation.symbolIndexingService,
        scope,
        sourceChangeDispatcher = Executor { paused.addLast(it) }.asCoroutineDispatcher(),
    )

    private lateinit var files: List<Path>
    private lateinit var uris: List<URI>
    private lateinit var open: URI
    private lateinit var sample: List<URI>

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `a large notification of closed source changes retires each file before it returns`(): Unit = runBlocking {
        val openParse = setUpWorkspace()

        val timings = linkedMapOf<String, Double>()
        val warmMedians = HashMap<Int, Double>()
        for (size in listOf(LARGE, SMALL)) {
            val runs = measureNotifications(size, openParse)
            // The first call of each size is dropped; the 500-event runs also follow every 5,000-event run.
            warmMedians[size] = median(runs.drop(1))
            timings["didChangeWatchedFiles, $size events, cold"] = runs.first()
            timings["didChangeWatchedFiles, $size events, warm (median of ${RUNS - 1})"] = median(runs.drop(1))
        }
        // The per-event fingerprint lookup, unused by a watcher retirement (keepIfUnchanged = false): its share.
        val manager = compilation.workspaceManager
        val fingerprints = (0 until RUNS).map { timed { uris.forEach { manager.fingerprintFor(it) } } }
        timings["$LARGE fingerprintFor calls alone, cold"] = fingerprints.first()
        timings["$LARGE fingerprintFor calls alone, warm (median of ${RUNS - 1})"] = median(fingerprints.drop(1))
        timings.forEach { (label, millis) ->
            println("[#1428] $label: ${String.format(Locale.ROOT, "%.2f", millis)} ms")
        }
        val warm = warmMedians.getValue(LARGE)
        val ratio = warm / warmMedians.getValue(SMALL)
        println("[#1428] warm scaling, $LARGE / $SMALL events: ${String.format(Locale.ROOT, "%.1f", ratio)}x")
        // Linear is about 10x, quadratic about 100x: a cheap quadratic passes the absolute budget, not this.
        assertTrue(ratio < MAX_SCALING, "$LARGE events cost ${ratio}x $SMALL events warm, over ${MAX_SCALING}x")
        // Backstop for a slowdown that scales linearly.
        assertTrue(warm < BUDGET_MILLIS, "$LARGE events took $warm ms warm, over the $BUDGET_MILLIS ms budget")

        // The drained queue re-retires the closed files and still skips the open buffer.
        while (paused.isNotEmpty()) paused.removeFirst().run()
        assertSame<Any?>(openParse, compilation.getParseResult(open), "the drain retired the open buffer")
        assertTrue(compilation.workspaceManager.getWorkspaceSources().containsAll(files), "changed files stay listed")
        val reread = assertNotNull(compilation.ensureCompiled(sample.first()))
        assertTrue("v = 2" in reread.sourceText.orEmpty(), "a retired file is recompiled from the new disk text")
    }

    @Test
    fun `a watcher retirement does not look up the fingerprint it never compares with`(): Unit = runBlocking {
        val manager = spyk(WorkspaceManager())
        val service = GroovyCompilationService(
            ioDispatcher = Dispatchers.Unconfined,
            documentProvider = DocumentProvider(),
            workspaceManager = manager,
        )
        val uri = tempDir.resolve("W.groovy").also { Files.writeString(it, "class W {}") }.toUri()
        assertNotNull(service.ensureCompiled(uri))

        clearMocks(manager, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)
        assertNotNull(service.onClosedSourceChanged(uri, keepIfUnchanged = false), "a closed file is retired")
        verify(exactly = 0) { manager.fingerprintFor(any<URI>()) }
        assertNull(service.getCompiledSnapshot(uri), "the parse was retired")

        // The close of an unchanged buffer still compares the cached text, under the fingerprint.
        assertNotNull(service.ensureCompiled(uri))
        clearMocks(manager, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)
        service.onClosedSourceChanged(uri, keepIfUnchanged = true)
        verify(atLeast = 1) { manager.fingerprintFor(any<URI>()) }
    }

    /** [LARGE] listed sources under one source root, F0 open as a buffer; returns the open buffer's parse. */
    private suspend fun setUpWorkspace(): Any {
        files = (0 until LARGE).map { index ->
            tempDir.resolve("F$index.groovy").also { Files.writeString(it, "class F$index { int v = 1 }") }
        }
        uris = files.map { it.toUri() }
        compilation.updateWorkspaceModel(tempDir, emptyList(), listOf(tempDir))
        assertEquals(LARGE, compilation.workspaceManager.getWorkspaceSources().size)

        open = uris.first()
        documents.put(open, "class Open {}")
        compilation.compile(open, "class Open {}")
        sample = uris.indices.filter { it != 0 && it % SAMPLE_STRIDE == 0 }.map { uris[it] }
        return assertNotNull(compilation.getParseResult(open))
    }

    /**
     * Sends [RUNS] notifications of [size] Changed events (the first [size] files, F0 open among them), each with
     * the sampled closed files cached and then edited on disk, and returns each call's duration in milliseconds.
     */
    private suspend fun measureNotifications(size: Int, openParse: Any): List<Double> {
        val events = uris.take(size).map { FileEvent(it.toString(), FileChangeType.Changed) }
        val cached = sample.filter { uris.indexOf(it) < size }
        return (0 until RUNS).map { run ->
            cached.forEach { uri ->
                val file = Path.of(uri)
                val name = file.fileName.toString().removeSuffix(".groovy")
                Files.writeString(file, "class $name { int v = 1 }")
                compilation.ensureCompiled(uri)
                assertNotNull(compilation.getCompiledSnapshot(uri), "sample not cached: retiring it is a no-op")
                Files.writeString(file, "class $name { int v = 2 }")
            }
            val millis = timed { workspace.didChangeWatchedFiles(DidChangeWatchedFilesParams(events)) }
            // Retired on the notification itself, while its queued work still waits.
            assertFalse(paused.isEmpty(), "the queued work must not have run")
            cached.forEach { uri ->
                assertNull(compilation.getCompiledSnapshot(uri), "run $run of $size events: old parse of $uri served")
            }
            assertSame<Any?>(openParse, compilation.getParseResult(open), "the open buffer's parse was retired")
            millis
        }
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private inline fun timed(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    private companion object {
        const val LARGE = 5_000
        const val SMALL = 500
        const val SAMPLE_STRIDE = 100
        const val RUNS = 6
        const val NANOS_PER_MILLI = 1_000_000.0

        // A backstop, generous for slow runners (measured 47-92 ms warm); [MAX_SCALING] catches a cheap quadratic.
        const val BUDGET_MILLIS = 2_000.0

        // 10x the events: linear about 10, quadratic about 100; measured 13-17 on CI. Headroom for noise at 5 ms.
        const val MAX_SCALING = 40.0
    }
}
