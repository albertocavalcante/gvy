package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also {
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

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
