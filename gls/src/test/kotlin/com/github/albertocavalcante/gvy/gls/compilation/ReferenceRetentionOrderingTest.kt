// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceRetentionOrderingTest {
    enum class Window { CURRENT, RETIRED, REPLACED }

    @Test
    fun `newest disk publication installs its document after an intervening failed build clears the graph`(
        @TempDir root: Path,
    ) {
        val path = Files.writeString(root.resolve("Subject.groovy"), "class Subject { void current() {} }")
        PublicationBuildInterleave(path).use { it.verify() }
    }

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
                RetentionExpectations.verify(window, original, replacement, kept)
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

    @ParameterizedTest
    @EnumSource(Window::class)
    fun `same mode disk replay settles its ticket before a late different text`(window: Window, @TempDir root: Path) {
        val text = "class Subject { void current() {} }"
        val path = Files.writeString(root.resolve("Subject.groovy"), text)
        val uri = path.toUri()
        val work = ObservedWork(uri, window)
        val compilation = GroovyCompilationService(workerSessionDecorator = work::decorate)
        val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
        runBlocking {
            try {
                compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
                compilation.refreshClosedReferences(path, ParseMode.WORKSPACE)
                val original = assertNotNull(index.getDocument(uri))
                assertWorkspaceCoverage(compilation, uri, original)
                val late = async(Dispatchers.IO) { compilation.compile(uri, LATE) }
                work.late.awaitEntry()
                if (window == Window.RETIRED) {
                    assertNotNull(compilation.onClosedSourceChanged(uri, keepIfUnchanged = false))
                }
                compilation.refreshClosedReferences(path, ParseMode.WORKSPACE)
                val replay = assertNotNull(index.getDocument(uri))
                if (window == Window.RETIRED) assertNotSame(original, replay) else assertSame(original, replay)
                assertEquals(Phases.CANONICALIZATION, compilation.semanticPhaseOf(uri))
                assertWorkspaceCoverage(compilation, uri, replay)
                val expected = if (window == Window.REPLACED) {
                    Files.writeString(path, NEWEST)
                    compilation.refreshClosedReferences(path, ParseMode.WORKSPACE)
                    assertNotNull(index.getDocument(uri)).also { assertNotSame(replay, it) }
                } else {
                    replay
                }

                work.late.release()
                val completed = late.await()
                assertTrue(completed.isSuccess)
                assertEquals(LATE, completed.sourceText)
                val delivered = assertNotNull(index.getDocumentSnapshot(uri))
                assertSame(expected, delivered.document, "The older text displaced a newer same-mode replay")
                assertEquals(if (window == Window.REPLACED) NEWEST else text, delivered.sourceText)
                assertWorkspaceCoverage(compilation, uri, expected)
            } finally {
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

private object RetentionExpectations {
    fun verify(
        window: ReferenceRetentionOrderingTest.Window,
        original: SemanticDocument,
        replacement: SemanticDocument?,
        kept: SemanticDocument,
    ) {
        when (window) {
            ReferenceRetentionOrderingTest.Window.CURRENT ->
                assertSame(original, kept, "The newer disk request must keep the original")
            ReferenceRetentionOrderingTest.Window.RETIRED -> assertNotSame(original, kept)
            ReferenceRetentionOrderingTest.Window.REPLACED -> assertSame(replacement, kept)
        }
    }
}

/** Deliver the failed middle publication once, during either replay planning or the latest document build. */
private class PublicationBuildInterleave(path: Path) : AutoCloseable {
    private val uri = path.toUri()
    private val text = Files.readString(path)
    private val otherText = "class Subject { void obsolete() {} }"
    private val worker = InProcessWorkerSession(GroovyParserFacade())
    private val workspace = WorkspaceManager().current()
    private val sources = ReferenceSources(
        workspace, ArtifactOwnership().begin(uri), Any(), ParseMode.WORKSPACE, emptyList(), emptyMap(),
    )
    private val onDiskCheck = AtomicReference<(() -> Unit)?>()
    private val onReplayOrBuild = AtomicReference<(() -> Unit)?>()
    private val observedPaths = ComparedClasspath(emptyList()) { onReplayOrBuild.getAndSet(null)?.invoke() }
    private val first = parse(text, observedPaths)
    private val middle = parse(otherText)
    private val latest = parse(text)
    private val index = WorkspaceSymbolIndex(GroovySemanticDB())
    private var failedBuilds = 0
    private val indexer = SemanticIndexer(index, isOpen = {
        onDiskCheck.getAndSet(null)?.invoke()
        false
    }) { ast, file ->
        if (ast === middle.ast) {
            failedBuilds++
            error("Middle semantic document failed to build")
        }
        if (ast === latest.ast) onReplayOrBuild.getAndSet(null)?.invoke()
        SemanticDocumentBuilder(ast, file).build()
    }

    fun verify() {
        val firstProducer = ReferenceProducer(observedPaths, worker, sources, complete = true)
        val latestProducer = ReferenceProducer(emptyList(), worker, sources, complete = true)
        indexer.begin(uri).use { firstTicket ->
            indexer.begin(uri).use { middleTicket ->
                indexer.begin(uri).use { latestTicket ->
                    onDiskCheck.set {
                        publish(firstTicket, text, first, SemanticPublicationSource(producer = firstProducer))
                        val installed = assertNotNull(indexer.currentReferencePublication(uri))
                        assertSame(firstProducer, installed.producer)
                        assertSame(installed.document, index.getDocument(uri))
                        onReplayOrBuild.set {
                            publish(middleTicket, otherText, middle, SemanticPublicationSource())
                            assertNull(index.getDocument(uri))
                            assertNull(indexer.currentReferencePublication(uri))
                        }
                    }
                    publish(latestTicket, text, latest, SemanticPublicationSource(false, latestProducer))
                }
            }
        }
        assertEquals(1, failedBuilds, "The middle build must have removed the first document")
        val stored = assertNotNull(index.getDocumentSnapshot(uri), "The newest disk publication lost its document")
        val publication = assertNotNull(indexer.currentReferencePublication(uri))
        assertEquals(text, stored.sourceText)
        assertSame(stored.document, publication.document)
        assertSame(latestProducer, publication.producer)
        assertTrue(publication.diskConfirmed && publication.covers(stored.document))
    }

    private fun parse(content: String, classpath: List<Path> = emptyList()) = worker.parse(
        ParseRequest(uri, content, classpath, compilePhase = Phases.CANONICALIZATION),
    ).also {
        assertTrue(it.isSuccessful && !it.outcome.isDegraded)
        assertEquals(Phases.CANONICALIZATION, it.outcome.achievedPhase)
    }

    private fun publish(
        ticket: SemanticIndexer.Publication,
        content: String,
        parsed: ParseResult,
        source: SemanticPublicationSource,
    ) = indexer.publish(ticket, content, assertNotNull(parsed.ast), parsed.outcome.achievedPhase, source)

    override fun close() {
        listOf(first, middle, latest).forEach { it.compilationUnit.classLoader.close() }
    }

    private class ComparedClasspath(private val paths: List<Path>, private val onCompare: () -> Unit) :
        List<Path> by paths {
        override fun equals(other: Any?): Boolean {
            onCompare()
            return paths == other
        }

        override fun hashCode(): Int = paths.hashCode()
    }
}
