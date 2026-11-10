package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
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
 * Measures what one large watched-files notification costs on the LSP notification thread (#1428): every closed
 * source event is retired synchronously before [GroovyWorkspaceService.didChangeWatchedFiles] returns. Step 1
 * only reports the timings (`[#1428]` lines in the test output); the budget is set from them later. The
 * retirement semantics are asserted on every measured notification.
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
        for (size in listOf(LARGE, SMALL)) {
            val runs = measureNotifications(size, openParse)
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

        // The drained queue re-retires the closed files and still skips the open buffer.
        while (paused.isNotEmpty()) paused.removeFirst().run()
        assertSame<Any?>(openParse, compilation.getParseResult(open), "the drain retired the open buffer")
        assertTrue(compilation.workspaceManager.getWorkspaceSources().containsAll(files), "changed files stay listed")
        val reread = assertNotNull(compilation.ensureCompiled(sample.first()))
        assertTrue("v = 2" in reread.sourceText.orEmpty(), "a retired file is recompiled from the new disk text")
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
    }
}
