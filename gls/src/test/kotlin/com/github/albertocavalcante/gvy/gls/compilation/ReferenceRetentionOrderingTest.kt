// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceRetentionOrderingTest {
    enum class Window { CURRENT, RETIRED, REPLACED }

    @ParameterizedTest
    @EnumSource(Window::class)
    fun `late different text cannot undo a newer semantic publication decision`(window: Window, @TempDir root: Path) {
        Files.writeString(root.resolve("Declarations.groovy"), "class AuxiliaryBase {}")
        val path = Files.writeString(root.resolve("Subject.groovy"), DISK)
        val uri = path.toUri()
        val work = ObservedWork(uri, window)
        val manager = WorkspaceManager().apply { setTestClasspath(root, work::classpath) }
        val compilation = GroovyCompilationService(workspaceManager = manager, workerSessionDecorator = work::decorate)
        val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
        runBlocking {
            try {
                compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
                assertTrue(compilation.getWorkspaceCompiler().compileWorkspace().success)
                compilation.refreshClosedReferences(path, ParseMode.WORKSPACE)
                val original = assertNotNull(index.getDocument(uri))
                assertEquals(Phases.CANONICALIZATION, compilation.semanticPhaseOf(uri))
                assertWorkspaceCoverage(compilation, uri, original)
                assertNull(compilation.getCompiledContent(uri))

                // compile() has taken its semantic token before the worker starts; no legacy cache entry exists yet.
                val late = async(Dispatchers.IO) { compilation.compile(uri, LATE) }
                work.late.awaitEntry()
                if (window == Window.RETIRED) {
                    assertNotNull(compilation.onClosedSourceChanged(uri, keepIfUnchanged = false))
                }
                val navigation = async(Dispatchers.IO) { compilation.ensureCompiled(uri) }
                val replacement = if (window == Window.REPLACED) {
                    replaceWhileVerifying(compilation, path, original, work.verifying)
                } else {
                    null
                }
                navigation.await()
                val weak = assertNotNull(work.minimal)
                assertNotNull(weak.ast)
                assertTrue(weak.outcome.isDegraded)
                val kept = assertNotNull(index.getDocument(uri))
                when (window) {
                    Window.CURRENT -> assertSame(original, kept, "The newer disk request must keep the original")
                    Window.RETIRED -> assertNotSame(original, kept)
                    Window.REPLACED -> assertSame(replacement, kept)
                }
                work.late.release()
                val completed = late.await()
                assertTrue(completed.isSuccess)
                assertEquals(LATE, completed.sourceText)
                val delivered = assertNotNull(index.getDocumentSnapshot(uri))
                assertSame(kept, delivered.document, "The older supplied text displaced a newer publication decision")
                assertEquals(if (window == Window.REPLACED) NEWEST else DISK, delivered.sourceText)
                if (window == Window.RETIRED) {
                    assertEquals(weak.outcome.achievedPhase, compilation.semanticPhaseOf(uri))
                    assertIs<SnapshotQuery.Failure>(compilation.referenceReadiness(uri).query { it.modeFor(uri) })
                } else {
                    assertWorkspaceCoverage(compilation, uri, kept)
                }
            } finally {
                work.verifying.release()
                work.late.release()
                compilation.shutdown()
            }
        }
    }

    private suspend fun replaceWhileVerifying(
        compilation: GroovyCompilationService,
        path: Path,
        original: SemanticDocument,
        gate: Gate,
    ): SemanticDocument {
        gate.awaitEntry()
        // Deliver newer disk text explicitly while the older producer's classpath check is paused.
        Files.writeString(path, NEWEST)
        compilation.refreshClosedReferences(path, ParseMode.WORKSPACE)
        val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
        val changed = assertNotNull(index.getDocumentSnapshot(path.toUri()))
        assertEquals(NEWEST, changed.sourceText)
        assertNotSame(original, changed.document)
        gate.release()
        return changed.document
    }

    private suspend fun assertWorkspaceCoverage(
        service: GroovyCompilationService,
        uri: URI,
        expected: SemanticDocument,
    ) {
        val ready = service.referenceReadiness(uri).query { facts ->
            assertSame(expected, facts.index.getDocumentSnapshot(uri)?.document)
            facts.modeFor(uri)
        }
        assertEquals(ParseMode.WORKSPACE, assertIs<SnapshotQuery.Answer<*>>(ready).value)
    }

    private class ObservedWork(private val uri: URI, private val window: Window) {
        val late = Gate()
        val verifying = Gate()
        private val verifyNext = AtomicBoolean()

        @Volatile
        var minimal: ParseResult? = null
            private set

        fun classpath(): List<Path> {
            if (verifyNext.compareAndSet(true, false)) verifying.pause()
            return emptyList()
        }

        fun decorate(worker: WorkerSession) = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                if (request.uri == uri && request.content == LATE) {
                    check(request.parseMode == ParseMode.WORKSPACE)
                    late.pause()
                }
                val parsed = worker.parse(request)
                if (request.uri == uri && request.parseMode == ParseMode.MINIMAL) {
                    check(request.content == DISK)
                    minimal = parsed
                    verifyNext.set(window == Window.REPLACED)
                }
                return parsed
            }
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val resumed = CountDownLatch(1)

        fun pause() {
            entered.countDown()
            check(resumed.await(30, TimeUnit.SECONDS)) { "Publication interleave was never released" }
        }

        fun awaitEntry() = assertTrue(entered.await(20, TimeUnit.SECONDS), "Publication interleave was never reached")

        fun release() = resumed.countDown()
    }

    private companion object {
        const val DISK = "class Subject extends AuxiliaryBase { void current() {} }"
        const val LATE = "class Subject { void obsolete() {} }"
        const val NEWEST = "class Subject { void latest() {} }"
    }
}
