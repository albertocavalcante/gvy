// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotAcquisitionTest {
    @TempDir
    lateinit var tempDir: Path

    private val uri = URI.create("file:///Acquisition.groovy")
    private val source = "class Acquisition {}"
    private val documents = DocumentProvider()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val count = AtomicInteger()
    private val parsed = AtomicReference<ParseResult>()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    count.incrementAndGet()
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "parse was not released" }
                    return delegate.parse(request).also(parsed::set)
                }
            }
        },
    )

    @AfterEach
    fun tearDown() {
        release.countDown()
        service.shutdown()
    }

    @Test
    fun `one cancelled waiter leaves the shared snapshot available to another`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        val first = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(uri) }
        assertTrue(started.await(10, TimeUnit.SECONDS), "compile never started")
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(uri) }
        first.cancel()
        release.countDown()

        val result = assertIs<SnapshotAcquisition.Ready>(second.await())
        assertSame(parsed.get(), result.snapshot.parseResult)
        assertEquals(source, result.snapshot.sourceText)
        assertEquals(1, count.get())
    }

    @Test
    fun `a returned snapshot reports retirement after a workspace invalidation`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()
        val result = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
        assertTrue(result.isCurrent())

        service.clearCaches()

        assertFalse(result.isCurrent())
    }

    @Test
    fun `different phase and parse policy requests do not reuse an incompatible result`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()
        service.acquireSnapshot(uri, Phases.CONVERSION, ParseMode.WORKSPACE)
        service.acquireSnapshot(uri, Phases.CANONICALIZATION, ParseMode.WORKSPACE)
        service.acquireSnapshot(uri, Phases.CANONICALIZATION, ParseMode.MINIMAL)

        assertEquals(3, count.get())
    }

    @Test
    fun `a completed owned result is reused with its original parser result`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()
        val first = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
        val second = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))

        assertSame(first.snapshot.parseResult, second.snapshot.parseResult)
        assertEquals(1, count.get())
    }

    @Test
    fun `a missing source is distinct from a worker failure`(): Unit = runBlocking {
        assertIs<SnapshotAcquisition.Missing>(service.acquireSnapshot(URI.create("untitled:missing")))
        val failing = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = {
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = throw IOException("worker failed")
                }
            },
        )
        try {
            failing.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
            val result = assertIs<SnapshotAcquisition.Failure>(failing.acquireSnapshot(uri))
            assertEquals("worker failed", result.cause?.message)
        } finally {
            failing.shutdown()
        }
    }

    @Test
    fun `an empty query answer is rejected when its snapshot is retired during the query`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()

        val result = service.querySnapshot(uri) {
            service.clearCaches()
            emptyList<String>()
        }

        assertIs<SnapshotQuery.Stale>(result)
    }

    @Test
    fun `invalidation during a compile returns stale rather than its old result`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        val answer = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(uri) }
        assertTrue(started.await(10, TimeUnit.SECONDS), "compile never started")

        service.clearCaches()

        assertIs<SnapshotAcquisition.Stale>(answer.await())
    }

    @Test
    fun `an incomplete current source remains an explicit partial result`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, "class Acquisition { void broken( }", DocumentVersion.of(1))
        release.countDown()

        val result = assertIs<SnapshotAcquisition.Partial>(service.acquireSnapshot(uri))
        assertEquals("class Acquisition { void broken( }", result.snapshot.sourceText)
    }

    @Test
    fun `cancellation prevents a query from using an already cached snapshot`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()
        assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
        var reachedQuery = false
        var queried = false
        val cancelled = async {
            currentCoroutineContext().cancel()
            reachedQuery = true
            assertFailsWith<CancellationException> { service.querySnapshot(uri) { queried = true } }
        }
        assertFailsWith<CancellationException> { cancelled.await() }
        assertTrue(reachedQuery)
        assertFalse(queried)
    }

    @ParameterizedTest
    @CsvSource("1, true", "1, false", "2, true", "2, false")
    fun `cancellation during freshness cannot compute or return a query`(cancelAt: Int, stillCurrent: Boolean): Unit =
        runBlocking {
            service.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
            release.countDown()
            val parsed = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri)).snapshot
            var computed = false
            var cancellationObserved = false
            val request = async {
                val caller = currentCoroutineContext()
                var checks = 0
                val available = SnapshotAcquisition.Ready(parsed) {
                    checks++
                    if (checks == cancelAt) caller.cancel()
                    checks != cancelAt || stillCurrent
                }
                try {
                    available.query {
                        computed = true
                        emptyList<String>()
                    }
                } catch (_: CancellationException) {
                    cancellationObserved = true
                }
            }
            assertFailsWith<CancellationException> { request.await() }
            request.join()
            assertTrue(cancellationObserved)
            assertEquals(cancelAt == 2, computed)
        }

    @Test
    fun `a closed companion change cannot allow old work to replace the newer result`(): Unit = runBlocking {
        val target = tempDir.resolve("Target.groovy")
        val companion = tempDir.resolve("Companion.groovy")
        Files.writeString(target, "class Target { Companion value }")
        Files.writeString(companion, "class Companion { def oldMethod() {} }")
        val targetUri = target.toUri()
        val companionUri = companion.toUri()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger()
        val heldService = GroovyCompilationService(workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri == targetUri && calls.incrementAndGet() == 1) {
                        firstStarted.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS)) { "first parse was not released" }
                    }
                    return delegate.parse(request)
                }
            }
        })
        try {
            heldService.updateWorkspaceModel(tempDir, emptyList(), listOf(tempDir))
            val older = async(Dispatchers.Default) { heldService.acquireSnapshot(targetUri) }
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "first parse never started")
            Files.writeString(companion, "class Companion { def newMethod() {} }")
            heldService.onClosedSourceChanged(companionUri, keepIfUnchanged = false)
            val newer = async(Dispatchers.Default) { heldService.acquireSnapshot(targetUri) }
            val fresh = assertIs<SnapshotAcquisition.Ready>(withTimeout(10_000) { newer.await() })
            releaseFirst.countDown()
            assertIs<SnapshotAcquisition.Stale>(withTimeout(10_000) { older.await() })
            val retained = assertIs<SnapshotAcquisition.Ready>(heldService.acquireSnapshot(targetUri))
            assertSame(fresh.snapshot.parseResult, retained.snapshot.parseResult)
            assertEquals(2, calls.get())
        } finally {
            releaseFirst.countDown()
            heldService.shutdown()
        }
    }

    @Test
    fun `a strategy classpath change retires a result with the same model`(): Unit = runBlocking {
        val classpath = AtomicReference(listOf(tempDir.resolve("old.jar")))
        val strategy = object : ProjectStrategy {
            override val id = "mutable-test"
            override val displayName = "Mutable test"
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun overridesClasspath(uri: URI) = true
            override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>) =
                classpath.get()
        }
        val registry = ProjectStrategyRegistry(listOf(strategy))
        registry.selectStrategies(tempDir, ServerConfiguration())
        val manager = WorkspaceManager()
        manager.updateWorkspaceModel(tempDir, emptyList(), listOf(tempDir))
        manager.setStrategyRegistry(registry)
        val targetDocuments = DocumentProvider()
        val held = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val requestPaths = mutableListOf<List<Path>>()
        val guarded = GroovyCompilationService(
            documentProvider = targetDocuments,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        synchronized(requestPaths) { requestPaths.add(request.classpath) }
                        entered.countDown()
                        check(held.await(10, TimeUnit.SECONDS)) { "parse was not released" }
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            guarded.onDocumentOpened(uri)
            targetDocuments.put(uri, source, DocumentVersion.of(1))
            val old = async(start = CoroutineStart.UNDISPATCHED) { guarded.acquireSnapshot(uri) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            classpath.set(listOf(tempDir.resolve("new.jar")))
            held.countDown()

            assertIs<SnapshotAcquisition.Stale>(withTimeout(10_000) { old.await() })
            assertIs<SnapshotAcquisition.Ready>(guarded.acquireSnapshot(uri))
            assertEquals(listOf(listOf(tempDir.resolve("old.jar")), listOf(tempDir.resolve("new.jar"))), requestPaths)
        } finally {
            held.countDown()
            guarded.shutdown()
        }
    }

    @Test
    fun `a ticket taken during shadow refresh is retired afterward`(): Unit = runBlocking {
        val real = Files.createDirectories(tempDir.resolve("real"))
        Files.writeString(real.resolve("Target.groovy"), source)
        val link = Files.createSymbolicLink(tempDir.resolve("link"), real)
        val targetUri = link.resolve("Target.groovy").toUri()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        lateinit var manager: WorkspaceManager
        lateinit var previous: WorkspaceModel
        manager = WorkspaceManager(
            ConfigurationFingerprint::compute,
            { false },
            normalisesNames = false,
            canonicalise = { path ->
                if (armed.get() && manager.current() !== previous) {
                    entered.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS)) { "shadow refresh was not released" }
                }
                DocumentTargetSelector.canonical(path)
            },
        )
        val localDocuments = DocumentProvider()
        val local = GroovyCompilationService(documentProvider = localDocuments, workspaceManager = manager)
        try {
            local.updateWorkspaceModel(tempDir, emptyList(), listOf(real))
            local.onDocumentOpened(targetUri)
            localDocuments.put(targetUri, source, DocumentVersion.of(1))
            previous = manager.current()
            armed.set(true)
            val added = Files.writeString(real.resolve("Added.groovy"), "class Added {}")
            val update = async(Dispatchers.IO) { local.onWorkspaceSourcesChanged(listOf(added), emptyList()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "shadow refresh never reached the gate")
            val ticket = local.compilationCacheService.ownership.begin(targetUri)
            proceed.countDown()
            update.await()

            assertFalse(local.compilationCacheService.ownership.isCurrent(ticket))
        } finally {
            proceed.countDown()
            local.shutdown()
        }
    }

    @Test
    fun `a class locator miss that becomes a hit invalidates the captured query`(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val calls = AtomicInteger()
        val observed = mutableListOf<List<Path>>()
        val targetDocuments = DocumentProvider()
        val local = GroovyCompilationService(
            documentProvider = targetDocuments,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        if (request.uri == uri) {
                            val locator = assertNotNull(request.workspaceClassLocator)
                            synchronized(observed) { observed.add(locator("pkg.NeedsLookup")) }
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown()
                                check(proceed.await(10, TimeUnit.SECONDS)) { "locator query was not released" }
                            }
                        }
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            local.updateWorkspaceModel(tempDir, emptyList(), listOf(tempDir))
            local.onDocumentOpened(uri)
            targetDocuments.put(uri, source, DocumentVersion.of(1))
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                local.acquireSnapshot(uri, mode = ParseMode.MINIMAL)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "locator was not called")
            val dependency = Files.writeString(tempDir.resolve("Other.groovy"), "package pkg; class NeedsLookup {}")
            assertNotNull(local.symbolIndexingService.indexFile(dependency.toUri()))
            proceed.countDown()

            assertIs<SnapshotAcquisition.Stale>(withTimeout(10_000) { old.await() })
            assertIs<SnapshotAcquisition.Ready>(local.acquireSnapshot(uri, mode = ParseMode.MINIMAL))
            assertEquals(listOf(emptyList(), listOf(dependency)), observed)
        } finally {
            proceed.countDown()
            local.shutdown()
        }
    }

    @Test
    fun `a changed dependency graph invalidates the captured workspace source selection`(): Unit = runBlocking {
        val target = Files.writeString(tempDir.resolve("Target.groovy"), "class Target {}")
        val firstDependency = Files.writeString(tempDir.resolve("First.groovy"), "class First {}")
        Files.writeString(tempDir.resolve("Second.groovy"), "class Second {}")
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val calls = AtomicInteger()
        val selections = mutableListOf<List<Path>>()
        val local = GroovyCompilationService(workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri == target.toUri()) {
                        synchronized(selections) { selections.add(request.workspaceSources) }
                        if (calls.incrementAndGet() == 1) {
                            entered.countDown()
                            check(proceed.await(10, TimeUnit.SECONDS)) { "parse was not released" }
                        }
                    }
                    return delegate.parse(request)
                }
            }
        })
        try {
            local.updateWorkspaceModel(tempDir, emptyList(), listOf(tempDir))
            val old = async(Dispatchers.Default) { local.acquireSnapshot(target.toUri()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "parse did not start")
            local.getWorkspaceCompiler().dependencyGraph.addDependency(target.toUri(), firstDependency.toUri())
            proceed.countDown()

            assertIs<SnapshotAcquisition.Stale>(withTimeout(10_000) { old.await() })
            assertIs<SnapshotAcquisition.Ready>(local.acquireSnapshot(target.toUri()))
            assertTrue(selections[0] != selections[1], "the graph update must change the actual selection")
        } finally {
            proceed.countDown()
            local.shutdown()
        }
    }

    @Test
    fun `retiring a previously unseen source invalidates an in-flight acquisition`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        val answer = async(start = CoroutineStart.UNDISPATCHED) { service.acquireSnapshot(uri) }
        assertTrue(started.await(10, TimeUnit.SECONDS), "compile never started")

        service.invalidateCache(URI.create("file:///NeverTracked.groovy"))
        release.countDown()

        assertIs<SnapshotAcquisition.Stale>(withTimeout(10_000) { answer.await() })
    }

    @Test
    fun `a closed untracked source retirement prevents reuse of an earlier snapshot`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()
        val before = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))

        service.onClosedSourceChanged(URI.create("file:///Unlisted.groovy"), keepIfUnchanged = false)

        assertFalse(before.isCurrent())
        assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
        assertEquals(2, count.get())
    }

    @Test
    fun `an unexpected worker invariant exception propagates`(): Unit = runBlocking {
        val failing = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = {
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = error("broken invariant")
                }
            },
        )
        try {
            failing.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
            val error = assertFailsWith<IllegalStateException> { failing.acquireSnapshot(uri) }
            assertEquals("broken invariant", error.message)
        } finally {
            failing.shutdown()
        }
    }

    @Test
    fun `concurrent requests with different parse policies do not share one producer`(): Unit = runBlocking {
        val entered = CountDownLatch(2)
        val proceed = CountDownLatch(1)
        val calls = AtomicInteger()
        val targetDocuments = DocumentProvider()
        val local = GroovyCompilationService(
            documentProvider = targetDocuments,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        calls.incrementAndGet()
                        entered.countDown()
                        check(proceed.await(10, TimeUnit.SECONDS)) { "parses were not released" }
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            local.onDocumentOpened(uri)
            targetDocuments.put(uri, source, DocumentVersion.of(1))
            val workspace = async(start = CoroutineStart.UNDISPATCHED) {
                local.acquireSnapshot(uri, Phases.CONVERSION, ParseMode.WORKSPACE)
            }
            val minimal = async(start = CoroutineStart.UNDISPATCHED) {
                local.acquireSnapshot(uri, Phases.CANONICALIZATION, ParseMode.MINIMAL)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "both policies must start a separate parse")
            proceed.countDown()

            assertIs<SnapshotAcquisition.Ready>(withTimeout(10_000) { workspace.await() })
            assertIs<SnapshotAcquisition.Ready>(withTimeout(10_000) { minimal.await() })
            assertEquals(2, calls.get())
        } finally {
            proceed.countDown()
            local.shutdown()
        }
    }

    @Test
    fun `retirement wakes every incompatible waiter while both workers remain blocked`(): Unit = runBlocking {
        val entered = CountDownLatch(2)
        val proceed = CountDownLatch(1)
        val targetDocuments = DocumentProvider()
        val local = GroovyCompilationService(
            documentProvider = targetDocuments,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        entered.countDown()
                        check(proceed.await(10, TimeUnit.SECONDS)) { "parses were not released" }
                        return delegate.parse(request)
                    }
                }
            },
        )
        try {
            local.onDocumentOpened(uri)
            targetDocuments.put(uri, source, DocumentVersion.of(1))
            val workspace = async(start = CoroutineStart.UNDISPATCHED) {
                local.acquireSnapshot(uri, Phases.CONVERSION, ParseMode.WORKSPACE)
            }
            val minimal = async(start = CoroutineStart.UNDISPATCHED) {
                local.acquireSnapshot(uri, Phases.CANONICALIZATION, ParseMode.MINIMAL)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "both parses must enter")

            local.clearCaches()

            assertIs<SnapshotAcquisition.Stale>(withTimeout(3_000) { workspace.await() })
            assertIs<SnapshotAcquisition.Stale>(withTimeout(3_000) { minimal.await() })
            assertEquals(1L, proceed.count, "the workers must still be blocked when both waiters wake")
        } finally {
            proceed.countDown()
            local.shutdown()
        }
    }

    @Test
    fun `retirement while a query is suspended rejects its eventual result`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            service.querySnapshot(uri) {
                entered.complete(Unit)
                proceed.await()
                emptyList<String>()
            }
        }
        withTimeout(5_000) { entered.await() }

        service.clearCaches()
        proceed.complete(Unit)

        assertIs<SnapshotQuery.Stale>(withTimeout(3_000) { answer.await() })
    }

    @Test
    fun `a query cannot return an uncollected flow`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(1))
        release.countDown()

        assertFailsWith<IllegalStateException> { service.querySnapshot(uri) { flowOf("late") } }
    }
}
