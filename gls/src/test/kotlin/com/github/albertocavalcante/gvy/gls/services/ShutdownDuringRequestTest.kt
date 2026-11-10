package com.github.albertocavalcante.gvy.gls.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.crossfile.CrossFileOperationsHandler
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Shutting down with navigation in flight, or answering ContentModified, is not an error worth logging. */
class ShutdownDuringRequestTest {
    private val uri = URI.create("file:///Shutdown.groovy")
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val armed = AtomicBoolean(false)
    private val documents = DocumentProvider()
    private val compilation = spyk(
        GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also {
                        if (armed.compareAndSet(true, false)) {
                            entered.countDown()
                            released.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            },
        ),
    )
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    init {
        root.addAppender(logs)
    }

    @AfterEach
    fun tearDown() {
        released.countDown()
        requests.cancel()
        root.detachAppender(logs)
    }

    @Test
    fun `shutdown with a references request in flight logs no error`(): Unit = runBlocking {
        documents.put(uri, "class Shutdown { def m() { m() } }", 1)
        compilation.compile(uri, "class Shutdown { def m() { m() } }")
        // The index is now older than the buffer, so the request waits on a compile of the new text.
        documents.update(uri, "class Shutdown { def m() { m(); m() } }", 2)
        armed.set(true)
        val request = requests.async { handler().getReferences(uri.toString(), Position(0, 28), true) }
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the request never waited on a compile")

        // GroovyLanguageServer.doShutdown: requests first, then the compilation service.
        requests.cancel()
        compilation.shutdown()
        released.countDown()
        runCatching { request.await() }

        assertTrue(request.isCancelled, "the request must end cancelled")
        val errors = logs.list.filter { it.level.isGreaterOrEqual(Level.ERROR) }
        assertEquals(emptyList(), errors.map { it.formattedMessage }, "shutdown logged errors")
    }

    @Test
    fun `after shutdown a waiter is cancelled at once`(): Unit = runBlocking {
        documents.put(uri, "class Shutdown {}", 1)
        compilation.shutdown()

        assertFailsWith<CancellationException> { compilation.ensureCompiled(uri) }
    }

    @Test
    fun `references propagate ContentModified to the client without logging an error`(): Unit = runBlocking {
        documents.put(uri, "class Shutdown {}", 1)
        coEvery { compilation.ensureCompiled(uri) } throws
            ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "changed", null))

        val error = assertFailsWith<ResponseErrorException> {
            handler().getReferences(uri.toString(), Position(0, 7), true)
        }

        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        assertTrue(logs.list.none { it.level.isGreaterOrEqual(Level.ERROR) })
    }

    private fun handler() =
        CrossFileOperationsHandler(compilation, documents, mockk<SourceNavigator>(relaxed = true), { false }, {})

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
