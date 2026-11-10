package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import java.util.concurrent.Semaphore
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
    private var beforeParse: () -> Unit = {}
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult =
                    beforeParse().let { delegate.parse(request) }.also {
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
     * Owners of shared compiles of one open buffer, each started the moment the previous one ends cancelled,
     * like debounced diagnostics superseded by typing. Each owner's parse waits at [gate] until the test
     * releases it, so a waiter is always parked on the newest owner before that owner is cancelled.
     */
    private inner class Owners(
        private val uri: URI,
        private val text: () -> String,
        /** Runs when an owner ends cancelled, before its successor starts (for example the user's next edit). */
        private val beforeReplacement: () -> Unit = {},
    ) {
        val gate = Semaphore(0)

        /** One permit per owner parse that has reached the gate, so a release always frees a waiting parse. */
        val arrived = Semaphore(0)
        val scopes = CopyOnWriteArrayList<CoroutineScope>()
        val jobs = CopyOnWriteArrayList<Deferred<CompilationResult>>()

        fun start() {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
            val job = service.compileAsync(owner, uri, text()).also(jobs::add)
            // Registered before any waiter awaits this job, so the next owner exists before the waiter resumes.
            job.invokeOnCompletion { failure ->
                if (failure is CancellationException) {
                    beforeReplacement()
                    start()
                }
            }
        }

        /** Cancels the newest owner, lets its parse finish, and returns once the waiter has re-resolved. */
        suspend fun cancelNewest() {
            val job = jobs.last()
            // Cancel only once its parse is running: a job cancelled before it starts would leave a spare permit
            check(withContext(Dispatchers.IO) { arrived.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS) }) { "no parse" }
            scopes.last().cancel()
            gate.release()
            job.join()
            yield()
        }
    }

    /** A navigation request on the runBlocking loop; its own job, so a failure stays in the test's assertion. */
    private fun CoroutineScope.waitOn(uri: URI) =
        async(SupervisorJob(), start = CoroutineStart.UNDISPATCHED) { service.ensureCompiled(uri) }

    private fun gatedOwners(uri: URI, beforeReplacement: () -> Unit = {}, text: () -> String): Owners {
        armed.set(false)
        val owners = Owners(uri, text, beforeReplacement)
        beforeParse = {
            owners.arrived.release()
            check(owners.gate.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) { "parse never released" }
        }
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
        owners.gate.release()

        assertEquals("class Hover {}", waiter.await()?.sourceText, "the waiter must get the next owner's result")
        assertEquals(5, owners.jobs.size, "the waiter rode each superseding owner instead of giving up")
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
            owners.gate.release(owners.jobs.size)
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
        owners.gate.release(owners.jobs.size)
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
        owners.gate.release(owners.jobs.size)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val MAX_RESOLUTIONS = 3
        const val MAX_OWNER_CANCELLATIONS = 10
    }
}
