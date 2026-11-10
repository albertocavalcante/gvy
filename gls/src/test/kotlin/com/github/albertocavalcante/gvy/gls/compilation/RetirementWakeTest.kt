package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        val job = scope.async {
            release.await(HOLD_SECONDS, TimeUnit.SECONDS)
            CompilationResult.failure(emptyList())
        }
        val pending = cache.register(job)
        val waiters = List(WAITERS) { async(Dispatchers.IO) { pending.awaitUnlessRetired() } }

        cache.invalidate(uri)

        val answers = withTimeout(WAIT_SECONDS * 1_000) { waiters.awaitAll() }
        assertEquals(List(WAITERS) { null }, answers)
        assertFalse(job.isCompleted, "the waiters woke at the signal, not at the job's completion")
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
     * The completion handlers registered on [signal], read from kotlinx.coroutines' job state (pinned in
     * libs.versions.toml): empty when its state is `Empty`, the state's description otherwise. Skips the
     * test if a coroutines upgrade renamed the state. A GC probe proved unreliable here: the test runtime
     * keeps a finished coroutine reachable whether or not the signal does (#1429).
     */
    private fun handlersOf(signal: CompletableDeferred<Unit>): String {
        val field = generateSequence<Class<*>>(signal.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name.startsWith("_state") }
        assumeTrue(field != null, "kotlinx.coroutines no longer keeps a _state field")
        val state = checkNotNull(field).apply { isAccessible = true }.get(signal)
        return if (state?.javaClass?.simpleName == "Empty") "" else state.toString()
    }

    private companion object {
        const val WAIT_SECONDS = 10L

        /** Longer than every assertion's own wait: a test that passes never needs a held parse to give up. */
        const val HOLD_SECONDS = 60L
        const val MAX_RESOLUTIONS = 3
        const val WAITERS = 4
        val NOT_SIGNALLED = Any()
    }
}
