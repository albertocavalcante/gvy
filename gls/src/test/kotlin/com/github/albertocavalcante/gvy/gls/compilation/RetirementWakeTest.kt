package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A waiter on a job the service retires re-resolves at the retirement, not after the stale compile finishes
 * (#1429). The wake is classified exactly as the completion-time path classifies the same event (#1390).
 */
class RetirementWakeTest {
    private val uri = URI.create("file:///Held.groovy")

    /** Parses of [uri] beyond the first [heldParses] are not held. Held parses wait for [release]. */
    @Volatile private var heldParses = Int.MAX_VALUE
    private val parses = AtomicInteger()
    private val parseStarted = Semaphore(0)
    private val release = CountDownLatch(1)
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri == uri) {
                        val n = parses.incrementAndGet()
                        parseStarted.release()
                        if (n <= heldParses) release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                    return delegate.parse(request)
                }
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        release.countDown()
        scope.cancel()
    }

    private fun open(text: String) {
        service.onDocumentOpened(uri)
        documents.put(uri, text, 1)
    }

    private suspend fun awaitParse() =
        check(withContext(Dispatchers.IO) { parseStarted.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS) }) { "no parse" }

    private fun CoroutineScope.waiter() =
        async(SupervisorJob(), start = CoroutineStart.UNDISPATCHED) { service.ensureCompiled(uri) }

    @Test
    fun `a global clear wakes a waiter before the retired compile finishes`(): Unit = runBlocking {
        open("class Held {}")
        heldParses = 1 // only the owner's parse is held; the waiter's fresh compile runs
        val owner = service.compileAsync(scope, uri, "class Held {}")
        awaitParse()
        val waiter = waiter()

        service.clearCaches()
        val answer = withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }

        assertNotNull(answer)
        assertFalse(owner.isCompleted, "the waiter waited for the retired compile to finish")
    }

    @Test
    fun `retirement wakes count toward the bound of three`(): Unit = runBlocking {
        open("class Held {}")
        service.compileAsync(scope, uri, "class Held {}") // every parse stays held
        awaitParse()
        val waiter = waiter()

        val error = assertFailsWith<ResponseErrorException> {
            repeat(MAX_RESOLUTIONS) { round ->
                service.invalidateCache(uri) // a retirement: the ticket is stale
                if (round < MAX_RESOLUTIONS - 1) awaitParse() // the waiter's own fresh compile, held too
            }
            withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }
        }

        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    @Test
    fun `a wake on a document change counts as a retirement`(): Unit = runBlocking {
        open("class Held1 {}")
        service.compileAsync(scope, uri, "class Held1 {}")
        awaitParse()
        val waiter = waiter()

        val error = assertFailsWith<ResponseErrorException> {
            repeat(MAX_RESOLUTIONS) { round ->
                documents.put(uri, "class Held${round + 2} {}", round + 2) // the waiter's own document changed
                service.compilationCacheService.invalidate(uri) // job cancelled, ticket still current
                if (round < MAX_RESOLUTIONS - 1) awaitParse()
            }
            withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }
        }

        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    @Test
    fun `a wake on an unchanged document with a current ticket stays an uncounted owner cancellation`(): Unit =
        runBlocking {
            open("class Held {}")
            service.compileAsync(scope, uri, "class Held {}")
            awaitParse()
            val waiter = waiter()

            repeat(MAX_RESOLUTIONS + 1) {
                service.compilationCacheService.invalidate(uri) // cancelled, nothing the waiter reads changed
                awaitParse()
            }
            release.countDown()

            assertNotNull(withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }, "an owner cancellation must not count")
        }

    @Test
    fun `a waiter resumed inline re-resolves after the fence and its compile keeps its facts`(): Unit =
        assertWakeKeepsFacts(Dispatchers.Unconfined)

    @Test
    fun `a dispatched waiter's compile keeps its facts after a retirement wake`(): Unit =
        assertWakeKeepsFacts(Dispatchers.IO)

    /**
     * An Unconfined waiter resumes on the retiring thread. Woken while the fence is held (a reentrant monitor),
     * it would take its new ticket mid-cleanup, and the cleanup's remaining steps would erase that compile's
     * semantic publication. The dispatched case is the control: it shows the semantic document is observable.
     */
    private fun assertWakeKeepsFacts(dispatcher: CoroutineDispatcher): Unit = runBlocking {
        open("class Held {}")
        heldParses = 1 // only the owner's parse is held; the waiter's fresh compile runs
        val owner = service.compileAsync(scope, uri, "class Held {}")
        awaitParse()
        val waiter = async(SupervisorJob() + dispatcher, start = CoroutineStart.UNDISPATCHED) {
            service.ensureCompiled(uri)
        }

        service.invalidateCache(uri)
        val answer = withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }

        assertEquals("class Held {}", answer?.sourceText)
        assertFalse(owner.isCompleted, "the waiter waited for the retired compile to finish")
        assertNotNull(
            service.getWorkspaceSymbolIndex()?.getDocument(uri),
            "the retirement's cleanup erased the semantic publication of the waiter's fresh compile",
        )
    }

    private fun CompilationCacheService.register(job: Deferred<CompilationResult>): PendingCompilation =
        registerPending(uri, CompilationIdentity("class Pending {}", "fp", null), ownership.begin(uri)) { job }

    @Test
    fun `invalidation removes the retired job before it signals`() {
        val cache = CompilationCacheService()
        val pending = cache.register(CompletableDeferred())
        val seen = AtomicReference<Any?>(NOT_SIGNALLED)
        pending.retired.invokeOnCompletion { seen.set(cache.getActiveCompilation(uri)) }

        cache.invalidate(uri)

        assertNotSame(NOT_SIGNALLED, seen.get(), "invalidation never signalled the retirement")
        assertNull(seen.get(), "a woken waiter could still select the retired job")
    }

    @Test
    fun `a global clear detaches every job before it signals`() {
        val cache = CompilationCacheService()
        val pending = cache.register(CompletableDeferred())
        val seen = AtomicReference<Any?>(NOT_SIGNALLED)
        pending.retired.invokeOnCompletion { seen.set(cache.getActiveCompilation(uri)) }

        cache.cancelDetached(cache.detach())

        assertNotSame(NOT_SIGNALLED, seen.get(), "the clear never signalled the retirement")
        assertNull(seen.get(), "a woken waiter could still select the retired job")
        assertTrue(pending.deferred.isCancelled, "the retired job must still be cancelled")
    }

    @Test
    fun `every waiter on a retired job wakes once with no result`(): Unit = runBlocking {
        val cache = CompilationCacheService()
        // A compile that ignores cancellation: the job completes only once its blocking body returns
        val entered = CountDownLatch(1)
        val job = scope.async {
            entered.countDown()
            release.await(HOLD_SECONDS, TimeUnit.SECONDS)
            CompilationResult.failure(emptyList())
        }
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the blocking compile never started")
        val pending = cache.register(job)
        val waiters = List(WAITERS) {
            async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { pending.awaitUnlessRetired() }
        }
        assertTrue(waiters.none { it.isCompleted }, "each waiter must register before retirement")

        cache.invalidate(uri)

        val answers = withTimeout(WAIT_SECONDS * 1_000) { waiters.awaitAll() }
        assertEquals(List(WAITERS) { null }, answers)
        assertFalse(job.isCompleted, "the waiters woke at the signal, not at the job's completion")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `retiring a queued job completes it without entering its blocking body`(): Unit = runTest {
        val cache = CompilationCacheService()
        var entered = false
        val job = async(StandardTestDispatcher(testScheduler)) {
            entered = true
            CompilationResult.failure(emptyList())
        }
        val pending = cache.register(job)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { pending.awaitUnlessRetired() }

        cache.invalidate(uri)
        runCurrent()

        assertFalse(entered, "retirement before dispatch must not enter the body")
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted, "a compile is noncooperative only after its blocking body starts")
        assertNull(waiter.await())
    }

    @Test
    fun `a job that completes normally never signals and leaves no handler on the signal`(): Unit = runBlocking {
        val job = CompletableDeferred<CompilationResult>()
        val pending = CompilationCacheService().register(job)
        // Undispatched: both select clauses are registered before the job completes
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { pending.awaitUnlessRetired() }
        assertTrue(handlersOf(pending.retired).isNotEmpty(), "the waiter never registered on the signal")

        job.complete(CompilationResult.failure(emptyList()))

        assertNotNull(waiter.await())
        assertFalse(pending.retired.isCompleted, "a normal completion must not signal a retirement")
        assertEquals("", handlersOf(pending.retired), "the losing select clause stayed registered on the signal")
    }

    /**
     * The completion handlers registered on [signal], read from kotlinx.coroutines' internal job state: empty
     * when that state is `Empty`, the state's description otherwise. Verified against kotlinx.coroutines
     * [VERIFIED_COROUTINES]; any other version fails loudly rather than passing on internals it was not checked
     * against. A GC probe proved unreliable here: the test runtime keeps a finished coroutine reachable whether
     * or not the signal does (#1429).
     */
    private fun handlersOf(signal: CompletableDeferred<Unit>): String {
        val version = Job::class.java.getResource("/META-INF/kotlinx_coroutines_core.version")?.readText()?.trim()
        check(version == VERIFIED_COROUTINES) {
            "kotlinx.coroutines is $version, not $VERIFIED_COROUTINES: re-verify that a losing select clause " +
                "leaves the job state Empty and update VERIFIED_COROUTINES, or delete this test (#1429)"
        }
        val field = generateSequence<Class<*>>(signal.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name.startsWith("_state") }
        checkNotNull(field) {
            "kotlinx.coroutines $version no longer keeps a _state field: re-verify the handler check or delete " +
                "this test (#1429)"
        }
        val state = field.apply { isAccessible = true }.get(signal)
        return if (state?.javaClass?.simpleName == "Empty") "" else state.toString()
    }

    private companion object {
        const val WAIT_SECONDS = 10L

        /** Longer than every assertion's own wait: a test that passes never needs a held parse to give up. */
        const val HOLD_SECONDS = 60L
        const val MAX_RESOLUTIONS = 3
        const val WAITERS = 4

        /** The kotlinx.coroutines version [handlersOf] reads the internals of (libs.versions.toml). */
        const val VERIFIED_COROUTINES = "1.10.2"
        val NOT_SIGNALLED = Any()
    }
}
