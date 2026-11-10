package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A caller awaiting shared compilation that is retired (by invalidation or a close) must get an answer for
 * the file's current inputs, never the retired inputs republished and never a cancellation it did not ask for.
 * Adapted from the #1260 concurrency review's reproduction.
 */
class RetiredWaiterTest {
    @TempDir
    lateinit var tempDir: Path

    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val armed = AtomicBoolean(true)

    /** Invoked after every parse, before the compile publishes. */
    private var afterParse: () -> Unit = {}

    /** Invoked before every parse; a compile cancelled meanwhile then aborts inside the parse, publishing nothing. */
    private var beforeParse: (ParseRequest) -> Unit = {}
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult =
                    beforeParse(request).let { delegate.parse(request) }.also {
                        parsed.computeIfAbsent(request.content) { AtomicInteger() }.incrementAndGet()
                        if (armed.compareAndSet(true, false)) {
                            entered.countDown()
                            check(released.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "never released" }
                        }
                        afterParse()
                    }
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        released.countDown()
        // A failed test must not leave owner parses parked on their latches for WAIT_SECONDS
        gated?.releaseAll()
        scope.cancel()
    }

    @Test
    fun `a waiter retired by closing an unsaved buffer is answered from disk`(): Unit = runBlocking {
        val file = tempDir.resolve("Closed.groovy").also { Files.writeString(it, "class OnDisk {}") }
        val uri = file.toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Unsaved {}", 1)

        val owner = service.compileAsync(scope, uri, "class Unsaved {}")
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        // A navigation request joins the buffer's pending compile: it runs until it suspends on that job.
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.ensureCompiled(uri) }

        // didClose: the buffer is removed and its closed source retired, cancelling the pending job.
        documents.remove(uri)
        assertNotNull(service.onClosedSourceChanged(uri))
        val answer = waiter.await()
        released.countDown()
        runCatching { owner.await() }

        assertEquals("class OnDisk {}", answer?.sourceText, "the waiter must re-resolve to the file on disk")
        val names = (service.getAst(uri) as? ModuleNode)?.classes?.map { it.nameWithoutPackage }.orEmpty()
        assertFalse("Unsaved" in names, "closed unsaved buffer republished after retirement: $names")
    }

    @Test
    fun `cancelling one waiting request does not retire the compile another waiter shares`(): Unit = runBlocking {
        val uri = tempDir.resolve("Shared.groovy").toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Shared {}", 1)

        val first = async(start = CoroutineStart.UNDISPATCHED) { service.ensureCompiled(uri) }
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first request never started compiling")
        val second = async(start = CoroutineStart.UNDISPATCHED) { service.ensureCompiled(uri) }
        first.cancel() // e.g. a superseded hover
        released.countDown()

        assertEquals("class Shared {}", second.await()?.sourceText, "the co-waiter must get the shared result")
        assertTrue(first.isCancelled)
        assertEquals(1, parsed["class Shared {}"]?.get(), "the shared compile must survive, not be redone")
        assertNotNull(service.getSymbolStorage(uri), "the shared compile still published")
    }

    @Test
    fun `a waiter whose compile is invalidated once gets a current result`(): Unit = runBlocking {
        val uri = tempDir.resolve("Open.groovy").toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Open {}", 1)
        armed.set(false)
        val invalidations = AtomicInteger()
        afterParse = { if (invalidations.getAndIncrement() == 0) service.invalidateCache(uri) }

        val answer = service.ensureCompiled(uri)

        assertEquals("class Open {}", answer?.sourceText)
        assertNotNull(service.getSymbolStorage(uri), "the re-resolved compile publishes")
    }

    @Test
    fun `a waiter invalidated on every attempt is told the content changed, not cancelled`(): Unit = runBlocking {
        val uri = tempDir.resolve("Churn.groovy").toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Churn {}", 1)
        armed.set(false)
        afterParse = { service.invalidateCache(uri) }

        val error = assertFailsWith<ResponseErrorException> { service.ensureCompiled(uri) }

        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    @Test
    fun `a closed file's in-flight semantic publication is retired with its parse`(): Unit = runBlocking {
        val sources = Files.createDirectories(tempDir.resolve("ws/src"))
        val file = Files.writeString(sources.resolve("Changed.groovy"), "class Before {}")
        service.updateWorkspaceModel(tempDir.resolve("ws"), emptyList(), listOf(sources))
        val compiling = scope.async { service.getWorkspaceCompiler().compileWorkspace() }
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "workspace compile never parsed")

        // The closed file changes on disk while its old text is compiled, and the watcher reports it.
        Files.writeString(file, "class After {}")
        assertNotNull(service.onClosedSourceChanged(file.toUri()))
        released.countDown()
        compiling.await()

        assertEquals(null, service.getWorkspaceSymbolIndex()?.getDocument(file.toUri()), "old text was published")
    }

    /**
     * Owners of shared compiles of one open buffer, each superseded by the next like debounced diagnostics
     * superseded by typing. Every owner has its own gate, found through [ownerIndex] in its parse, so a release
     * frees exactly that owner's parse; a parse that is not an owner's (the waiter's own job) is never gated.
     */
    private inner class Owners(
        private val uri: URI,
        private val text: () -> String,
        /** Runs when an owner is cancelled, before its successor starts (for example the user's next edit). */
        private val beforeReplacement: () -> Unit = {},
    ) {
        inner class Owner(val scope: CoroutineScope) {
            val parsing = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            lateinit var job: Deferred<CompilationResult>
        }

        val owners = CopyOnWriteArrayList<Owner>()
        val count: Int get() = owners.size

        fun start() {
            val tag = ownerIndex.asContextElement(owners.size)
            val owner = Owner(CoroutineScope(SupervisorJob() + Dispatchers.IO + tag))
            owners += owner // before the compile starts, so its parse finds its gate
            owner.job = service.compileAsync(owner.scope, uri, text())
        }

        /** Parses that belong to no owner; any one means the harness lost lockstep with the waiter. */
        val strays = CopyOnWriteArrayList<String>()

        /** Called from inside a parse: an owner's parse reports itself and waits for its own release. */
        fun gate(request: ParseRequest) {
            val index = ownerIndex.get()
            if (index == null) {
                // Recorded and reported at the next round: a third cause must not hide as a slow test
                strays += "${request.uri} ${request.content.take(STRAY_PREVIEW)} on ${Thread.currentThread().name}"
                return
            }
            val owner = owners[index]
            owner.parsing.complete(Unit)
            check(owner.release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "parse never released" }
        }

        fun releaseNewest() = owners.last().release.countDown()

        fun releaseAll() = owners.forEach { it.release.countDown() }

        /**
         * Cancels the newest owner and returns once the waiter has re-resolved onto its successor.
         * [settleMillis] stalls this thread after the release, as a loaded CI machine may (run 42800948915).
         */
        suspend fun cancelNewest(settleMillis: Long = 0) {
            assertNoStrays()
            val owner = owners.last()
            // Cancel only once its parse is running, so the cancellation aborts that parse
            checkNotNull(withTimeoutOrNull(WAIT_SECONDS * 1_000) { owner.parsing.await() }) { "no parse" }
            // Registered while the owner is provably incomplete (its parse is held), so it is queued, not run now
            val handled = CompletableDeferred<Unit>()
            owner.job.invokeOnCompletion { handled.complete(Unit) }
            owner.scope.cancel()
            // The successor exists before the cancelled owner can finish, so the waiter always finds it
            beforeReplacement()
            start()
            owner.release.countDown()
            if (settleMillis > 0) Thread.sleep(settleMillis)
            // Relies on runBlocking's FIFO event loop: the waiter's await handler was registered before
            // [handled]'s, so its resume is dispatched to this loop first, and it re-resolves onto the successor
            // without suspending until it awaits it. Only then does this coroutine resume.
            handled.await()
            assertNoStrays()
        }

        fun assertNoStrays() = check(strays.isEmpty()) { "un-owned parse(s); the waiter compiled on its own: $strays" }
    }

    /** Which [Owners.Owner] a parse belongs to, carried into the owner's compile coroutine. */
    private val ownerIndex = ThreadLocal<Int?>()

    /** A navigation request on the runBlocking loop; its own job, so a failure stays in the test's assertion. */
    private fun CoroutineScope.waitOn(uri: URI) =
        async(SupervisorJob(), start = CoroutineStart.UNDISPATCHED) { service.ensureCompiled(uri) }

    /** The owners of the running test, released in [tearDown] whatever its outcome. */
    private var gated: Owners? = null

    private fun gatedOwners(uri: URI, beforeReplacement: () -> Unit = {}, text: () -> String): Owners {
        armed.set(false)
        val owners = Owners(uri, text, beforeReplacement)
        gated = owners
        beforeParse = owners::gate
        return owners
    }

    @Test
    fun `owner cancellations of a shared compile do not exhaust a co-waiter's retries (#1343)`(): Unit = runBlocking {
        val uri = tempDir.resolve("Hover.groovy").toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Hover {}", 1)
        val owners = gatedOwners(uri) { "class Hover {}" }
        owners.start()
        val waiter = waitOn(uri)

        // More owner cancellations than the retirement bound of 3; the waiter's document never changes
        repeat(4) { owners.cancelNewest() }
        owners.releaseNewest()

        assertEquals("class Hover {}", waiter.await()?.sourceText, "the waiter must get the next owner's result")
        assertEquals(5, owners.count, "the waiter rode each superseding owner instead of giving up")
        owners.assertNoStrays()
    }

    @Test
    fun `a waiter whose own document changes on every owner cancellation still gets ContentModified`(): Unit =
        runBlocking {
            val uri = tempDir.resolve("Typing.groovy").toUri()
            service.onDocumentOpened(uri)
            var version = 1
            documents.put(uri, "class Typing1 {}", version)
            // The waiter's own document changes with every superseded owner, before the waiter re-resolves
            val owners = gatedOwners(uri, beforeReplacement = {
                version++
                documents.put(uri, "class Typing$version {}", version)
            }) { documents.getSnapshot(uri)!!.content }
            owners.start()
            val waiter = waitOn(uri)

            val error = assertFailsWith<ResponseErrorException> {
                repeat(MAX_RESOLUTIONS) { owners.cancelNewest() }
                withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }
            }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            owners.releaseAll()
            owners.assertNoStrays()
        }

    @Test
    fun `owner cancellations are bounded so a waiter never loops forever`(): Unit = runBlocking {
        val uri = tempDir.resolve("Endless.groovy").toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Endless {}", 1)
        val owners = gatedOwners(uri) { "class Endless {}" }
        owners.start()
        val waiter = waitOn(uri)

        val error = assertFailsWith<ResponseErrorException> {
            repeat(MAX_OWNER_CANCELLATIONS) { owners.cancelNewest() }
            withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }
        }

        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        owners.releaseAll()
        owners.assertNoStrays()
    }

    /**
     * Run 42800948915 failed the bounded test above on CI: the rounds lost lockstep with the waiter. Stalling
     * the test thread past the owner's completion, while the successor starts slowly, reproduces that here.
     */
    @Test
    fun `owner cancellations stay in lockstep when the owner finishes first and its successor starts slowly`(): Unit =
        runBlocking {
            val uri = tempDir.resolve("Loaded.groovy").toUri()
            service.onDocumentOpened(uri)
            documents.put(uri, "class Loaded {}", 1)
            val owners = gatedOwners(uri, beforeReplacement = { Thread.sleep(SLOW_SUCCESSOR_MILLIS) }) {
                "class Loaded {}"
            }
            owners.start()
            val waiter = waitOn(uri)

            val error = assertFailsWith<ResponseErrorException> {
                repeat(MAX_OWNER_CANCELLATIONS) { owners.cancelNewest(settleMillis = SETTLE_MILLIS) }
                withTimeout(WAIT_SECONDS * 1_000) { waiter.await() }
            }

            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            owners.releaseAll()
            owners.assertNoStrays()
        }

    @Test
    fun `a cancelled request stops re-resolving after an owner cancellation`(): Unit = runBlocking {
        val uri = tempDir.resolve("Gone.groovy").toUri()
        service.onDocumentOpened(uri)
        documents.put(uri, "class Gone {}", 1)
        val owners = gatedOwners(uri) { "class Gone {}" }
        owners.start()
        val waiter = waitOn(uri)

        owners.cancelNewest()
        waiter.cancel() // the client cancels the request while it waits on the next owner
        owners.cancelNewest()

        assertFailsWith<CancellationException> { withTimeout(WAIT_SECONDS * 1_000) { waiter.await() } }
        assertTrue(waiter.isCancelled, "a cancelled request must end, not keep re-resolving")
        owners.releaseAll()
        owners.assertNoStrays()
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val MAX_RESOLUTIONS = 3
        const val MAX_OWNER_CANCELLATIONS = 10

        /** Longer than [SETTLE_MILLIS], so the successor is still starting when the test thread resumes. */
        const val SLOW_SUCCESSOR_MILLIS = 50L
        const val SETTLE_MILLIS = 20L

        /** Characters of a stray parse's text shown in the failure. */
        const val STRAY_PREVIEW = 40
    }
}
