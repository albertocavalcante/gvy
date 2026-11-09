package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompilationJobOwnershipTest {
    @Test
    fun `different source requests do not reuse an active compilation`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))

        val first = fixture.orchestrator.compileAsync(this, fixture.uri, "class Before {}")
        val second = fixture.orchestrator.compileAsync(this, fixture.uri, "class After {}")

        assertNotSame(first, second)
        assertEquals("class Before {}", first.await().sourceText)
        assertEquals("class After {}", second.await().sourceText)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun `different configuration requests do not reuse an active compilation`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val first = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        fixture.fingerprint = "classpath-v2"

        val second = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        assertNotSame(first, second)
        first.await()
        second.await()
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun `compatible requests share one compilation`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))

        val first = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        val second = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        assertSame(first, second)
        first.await()
        assertEquals(1, fixture.requests.size)
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))
    }

    @Test
    fun `reopening identical content and version does not reuse the prior document job`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.documents.put(fixture.uri, fixture.content, 1)
        val first = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        fixture.documents.remove(fixture.uri)
        fixture.documents.put(fixture.uri, fixture.content, 1)

        val reopened = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        assertNotSame(first, reopened)
        first.await()
        reopened.await()
    }

    @Test
    fun `immediate completion leaves no job registered`(): Unit = runTest {
        val fixture = Fixture(Dispatchers.Unconfined)

        val result = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        assertTrue(result.isCompleted)
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))
        assertEquals(0, fixture.cache.getStatistics()["activeCompilations"])
    }

    @Test
    fun `a request reentering during dispatch shares the already registered job`(): Unit = runTest {
        val dispatcher = QueuedDispatcher()
        val fixture = Fixture(dispatcher)
        var reentrant: Deferred<CompilationResult>? = null
        dispatcher.onDispatch = {
            reentrant = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        }

        val first = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        dispatcher.runAll()

        assertSame(first, reentrant)
        assertEquals(1, fixture.requests.size)
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))
    }

    @Test
    fun `an older completion cannot remove a replacement registration`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        // Different text replaces the registration while the first job is still live (invalidation would
        // cancel it, completing it before the replacement exists).
        val first = fixture.orchestrator.compileAsync(this, fixture.uri, "class Before {}")
        lateinit var replacement: Deferred<CompilationResult>
        fixture.onParse = { request ->
            if (request.content == fixture.content) {
                assertSame(replacement, fixture.cache.getActiveCompilation(fixture.uri))
            }
        }
        replacement = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        first.await()
        replacement.await()

        assertEquals(2, fixture.requests.size)
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))
    }

    @Test
    fun `ensureCompiled uses the current open snapshot instead of an older active result`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.documents.put(fixture.uri, "class Before {}", 1)
        val first = fixture.orchestrator.compileAsync(this, fixture.uri, "class Before {}")
        fixture.documents.update(fixture.uri, fixture.content, 2)

        val current = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.orchestrator.ensureCompiled(fixture.uri)
        }

        assertEquals(fixture.content, assertNotNull(current.await()).sourceText)
        assertEquals("class Before {}", first.await().sourceText)
    }

    @Test
    fun `cancelling a separate waiter does not cancel shared compilation`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val waitingScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val fixture = Fixture(dispatcher)
            val work = fixture.orchestrator.compileAsync(owner, fixture.uri, fixture.content)
            val shared = fixture.orchestrator.compileAsync(waitingScope, fixture.uri, fixture.content)
            assertSame(work, shared)
            val waiter = waitingScope.async(start = CoroutineStart.UNDISPATCHED) { shared.await() }

            waitingScope.cancel()

            assertTrue(waiter.isCancelled)
            assertFalse(work.isCancelled)
            assertEquals(fixture.content, work.await().sourceText)
            assertEquals(1, fixture.requests.size)
        } finally {
            waitingScope.cancel()
            owner.cancel()
        }
    }

    @Test
    fun `an active ensureCompiled waiter retries when the original owner is cancelled`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val fixture = Fixture(dispatcher)
        fixture.documents.put(fixture.uri, fixture.content, 1)
        val abandoned = fixture.orchestrator.compileAsync(owner, fixture.uri, fixture.content)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.orchestrator.ensureCompiled(fixture.uri)
        }

        owner.cancel()

        assertEquals(fixture.content, assertNotNull(waiter.await()).sourceText)
        assertTrue(abandoned.isCancelled)
        assertEquals(1, fixture.requests.size)
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))
    }

    @Test
    fun `owner cancellation releases queued work and a new owner can compile`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val fixture = Fixture(dispatcher)
        val abandoned = fixture.orchestrator.compileAsync(owner, fixture.uri, fixture.content)

        owner.cancel()
        abandoned.join()
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))

        val current = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        assertNotSame(abandoned, current)
        assertEquals(fixture.content, current.await().sourceText)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `an already cancelled creator scope cannot leave a registered job`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        owner.cancel()
        val fixture = Fixture(dispatcher)

        val abandoned = fixture.orchestrator.compileAsync(owner, fixture.uri, fixture.content)
        abandoned.join()

        assertTrue(abandoned.isCancelled)
        assertNull(fixture.cache.getActiveCompilation(fixture.uri))
        assertEquals(0, fixture.cache.getStatistics()["activeCompilations"])
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `registered lazy work is shared before its creator starts it`(): Unit = runTest {
        val cache = CompilationCacheService()
        val uri = URI.create("file:///Pending.groovy")
        val identity = CompilationIdentity("class Pending {}", "classpath-v1", null)
        val lazyWork = async(start = CoroutineStart.LAZY) { CompilationResult.failure(emptyList()) }
        val registered = cache.registerCompilation(uri, identity) { lazyWork }
        assertFalse(registered.isActive)

        val reused = cache.registerCompilation(
            uri,
            CompilationIdentity("class Pending {}", "classpath-v1", null),
        ) { error("Compatible pending work must not be constructed twice") }

        assertSame(registered, reused)
        reused.await()
        assertNull(cache.getActiveCompilation(uri))
    }

    @Test
    fun `ensureCompiled preserves pending unversioned work without an open buffer`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        val work = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        val result = fixture.orchestrator.ensureCompiled(fixture.uri)

        assertEquals(fixture.content, assertNotNull(result).sourceText)
        assertSame(work.await(), result)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `closed acquisition cannot adopt requested text that mismatched the captured open buffer`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), uri = URI.create("untitled:Mismatched.groovy"))
        fixture.documents.put(fixture.uri, "class NewerBuffer {}", 2)
        val retired = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        fixture.documents.remove(fixture.uri)

        val closed = fixture.orchestrator.ensureCompiled(fixture.uri)

        assertNull(closed, "Observing a different open buffer must not classify requested text as unmanaged")
        assertEquals(fixture.content, retired.await().sourceText)
    }

    @Test
    fun `unmanaged work cannot share a mismatched open buffer request after close`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler))
        fixture.documents.put(fixture.uri, "class NewerBuffer {}", 2)
        val retired = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        fixture.documents.remove(fixture.uri)

        val unmanaged = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)

        assertNotSame(retired, unmanaged)
        assertEquals(fixture.content, retired.await().sourceText)
        assertEquals(fixture.content, unmanaged.await().sourceText)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun `closing during scheduling cannot reclassify an open request as unmanaged`(): Unit = runTest {
        val documents = spyk(DocumentProvider())
        val fixture = Fixture(StandardTestDispatcher(testScheduler), documents, URI.create("untitled:Retired.groovy"))
        documents.put(fixture.uri, fixture.content, 1)
        val captured = documents.getSnapshot(fixture.uri)
        var reads = 0
        every { documents.getSnapshot(fixture.uri) } answers {
            if (reads++ == 0) {
                captured
            } else {
                documents.remove(fixture.uri)
                null
            }
        }
        val original = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.orchestrator.ensureCompiled(fixture.uri)
        }

        assertNull(fixture.orchestrator.ensureCompiled(fixture.uri), "Closed callers cannot adopt retired buffer text")
        assertEquals(fixture.content, assertNotNull(original.await()).sourceText)
    }

    @Test
    fun `an already cancelled incompatible request cannot displace live work`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = Fixture(dispatcher)
        val live = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        val cancelledOwner = CoroutineScope(SupervisorJob() + dispatcher)
        cancelledOwner.cancel()

        val abandoned = fixture.orchestrator.compileAsync(cancelledOwner, fixture.uri, "class Abandoned {}")

        assertTrue(abandoned.isCancelled)
        assertSame(live, fixture.cache.getActiveCompilation(fixture.uri))
        assertSame(live, fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content))
        live.await()
        assertEquals(1, fixture.requests.size)
    }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        val documents: DocumentProvider = DocumentProvider(),
        val uri: URI = URI.create("file:///Example.groovy"),
    ) {
        val content = "class Example {}"
        val cache = CompilationCacheService()
        val requests = mutableListOf<ParseRequest>()
        var fingerprint = "classpath-v1"
        var onParse: (ParseRequest) -> Unit = {}
        val orchestrator: CompilationOrchestrator

        init {
            val worker = mockk<WorkerSessionManager>()
            every { worker.parse(any()) } answers {
                val request = firstArg<ParseRequest>()
                requests += request
                onParse(request)
                ParseResult(
                    ast = null,
                    compilationUnit = mockk(relaxed = true),
                    sourceUnit = mockk(relaxed = true),
                    diagnostics = emptyList(),
                    symbolTable = mockk(relaxed = true),
                    astModel = mockk(relaxed = true),
                    tokenIndex = null,
                )
            }
            val workspace = mockk<WorkspaceManager>()
            every { workspace.getConfigurationFingerprint() } answers { fingerprint }
            every { workspace.getClasspathForFile(any(), any()) } returns emptyList()
            every { workspace.getSourceRoots() } returns emptyList()
            every { workspace.getWorkspaceSources() } returns emptyList()
            orchestrator = CompilationOrchestrator(
                CompilationOrchestratorDependencies(
                    cacheService = cache,
                    workerSessionManager = worker,
                    workspaceManager = workspace,
                    symbolIndexer = mockk(relaxed = true),
                    parseAccessor = mockk(relaxed = true),
                    resultMapper = CompilationResultMapper(),
                    ioDispatcher = dispatcher,
                    errorHandler = CompilationErrorHandler(),
                    documentProvider = documents,
                ),
            )
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        var onDispatch: (() -> Unit)? = null
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val callback = onDispatch
            onDispatch = null
            callback?.invoke()
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
