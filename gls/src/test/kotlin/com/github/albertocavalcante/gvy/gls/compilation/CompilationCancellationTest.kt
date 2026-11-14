// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A compile must stop once the coroutine that asked for it is cancelled, instead of running to the end.
 *
 * The session below runs the real parser but cancels the calling coroutine from inside the cancellation
 * hook, so the cancel lands at an exact point in the compile and no timing is involved.
 */
class CompilationCancellationTest {

    private val uri = URI.create("file:///Big.groovy")
    private val content = (1..50).joinToString("\n") { "class C$it { int f() { $it } }" }

    private class CancellingSession(private val cancelAtCall: Int) : WorkerSession {
        val calls = AtomicInteger()
        val target = AtomicReference<Deferred<*>>()
        private val delegate = InProcessWorkerSession(GroovyParserFacade())

        override fun parse(request: ParseRequest): ParseResult = delegate.parse(
            request.copy(
                checkCancelled = {
                    if (calls.incrementAndGet() == cancelAtCall) target.get().cancel()
                    request.checkCancelled()
                },
            ),
        )
    }

    private fun orchestrator(
        session: WorkerSession,
        cacheService: CompilationCacheService = CompilationCacheService(),
    ): CompilationOrchestrator {
        val workspaceManager = mockk<WorkspaceManager>()
        every { workspaceManager.getClasspathForFile(any(), any(), any()) } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns emptyList()
        every { workspaceManager.getWorkspaceSources() } returns emptyList()
        every { workspaceManager.getConfigurationFingerprint() } returns "fp"
        every { workspaceManager.isExcludedSourceFile(any()) } returns false
        workspaceManager.delegateCompileContextToLegacyStubs()
        every { workspaceManager.current() } answers {
            testWorkspace("fp", sourceRoots = emptyList(), sources = emptyList())
        }
        return CompilationOrchestrator(
            CompilationOrchestratorDependencies(
                cacheService = cacheService,
                workerSessionManager = WorkerSessionManager(session) { error("no remote workers") },
                workspaceManager = workspaceManager,
                symbolIndexer = mockk(relaxed = true),
                parseAccessor = mockk(relaxed = true),
                resultMapper = CompilationResultMapper(),
                ioDispatcher = Dispatchers.Unconfined,
                errorHandler = CompilationErrorHandler(),
            ),
        )
    }

    @Test
    fun `compile stops early when its coroutine is cancelled`(): Unit = runBlocking {
        val session = CancellingSession(cancelAtCall = 2)
        val orchestrator = orchestrator(session)

        val deferred = async(Dispatchers.Default, start = CoroutineStart.LAZY) { orchestrator.compile(uri, content) }
        session.target.set(deferred)

        assertFailsWith<CancellationException> { deferred.await() }
        assertEquals(2, session.calls.get(), "no compiler phase may start after the cancel")
    }

    @Test
    fun `an uncancelled compile checks the hook once per phase`(): Unit = runBlocking {
        val session = CancellingSession(cancelAtCall = Int.MAX_VALUE)
        orchestrator(session).compile(uri, content)
        assertTrue(session.calls.get() > 2, "expected several checks, got ${session.calls.get()}")
    }

    @Test
    fun `transient compile stops early when its coroutine is cancelled`(): Unit = runBlocking {
        val session = CancellingSession(cancelAtCall = 2)
        val orchestrator = orchestrator(session)

        val deferred =
            async(Dispatchers.Default, start = CoroutineStart.LAZY) { orchestrator.compileTransient(uri, content) }
        session.target.set(deferred)

        assertFailsWith<CancellationException> { deferred.await() }
        assertEquals(2, session.calls.get())
    }

    @Test
    fun `a cancelled compile caches nothing`(): Unit = runBlocking {
        val session = CancellingSession(cancelAtCall = 2)
        val cache = CompilationCacheService()
        val orchestrator = orchestrator(session, cache)

        val deferred = async(Dispatchers.Default, start = CoroutineStart.LAZY) { orchestrator.compile(uri, content) }
        session.target.set(deferred)
        assertFailsWith<CancellationException> { deferred.await() }

        assertEquals(null, cache.getCached(uri, content, "fp"), "a cancelled compile must not be cached")
    }

    @Test
    fun `an on-demand compile from disk propagates cancellation instead of returning null`(): Unit = runBlocking {
        val file = kotlin.io.path.createTempFile("OnDisk", ".groovy").also { it.toFile().writeText(content) }
        try {
            // The hook throws as a cancelled caller's ensureActive() would, without cancelling this
            // coroutine, so the result is observable: swallowing it would return null instead.
            val throwing = object : WorkerSession {
                private val delegate = InProcessWorkerSession(GroovyParserFacade())
                override fun parse(request: ParseRequest): ParseResult =
                    delegate.parse(request.copy(checkCancelled = { throw CancellationException("cancelled") }))
            }

            assertFailsWith<CancellationException> { orchestrator(throwing).ensureCompiled(file.toUri()) }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}
