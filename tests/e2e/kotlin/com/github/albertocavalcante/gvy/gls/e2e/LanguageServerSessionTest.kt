package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LanguageServerSessionTest {
    @Test
    fun `in-process exit waits for the observed successful exit code`() {
        val server = SessionTestServer()
        inProcessSession(server).use { session ->
            session.server.shutdown().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.exit()
        }
        assertThat(server.exitCalls).isEqualTo(1)
    }

    @Test
    fun `an exit before shutdown can explicitly expect the protocol error code`() {
        inProcessSession(SessionTestServer()).use { it.exit(expectedCode = 1) }
    }

    @Test
    fun `in-process exit fails when its observed code differs from the expectation`() {
        inProcessSession(SessionTestServer()).use { session ->
            assertThatThrownBy { session.exit() }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("code 1 (expected 0)")
        }
    }

    @Test
    fun `an ignored in-process exit times out instead of becoming a successful cleanup`() {
        val server = SessionTestServer()
        val factory = LanguageServerSessionFactory(createServer = { server })
        factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "ignored-exit").use { session ->
            assertThatThrownBy { session.exit(timeout = Duration.ofMillis(EXIT_WAIT_MS)) }
                .isInstanceOf(TimeoutException::class.java)
        }
    }

    @Test
    fun `subprocess exit validates the reported process exit status`() {
        processSession(ControlledProcess(code = 0)).use { it.exit() }
        processSession(ControlledProcess(code = NONZERO_EXIT)).use { session ->
            assertThatThrownBy { session.exit() }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("code $NONZERO_EXIT (expected 0)")
        }
    }

    @Test
    fun `a subprocess that ignores exit cannot pass through forced teardown`() {
        processSession(ControlledProcess(code = 0, completed = false)).use { session ->
            assertThatThrownBy { session.exit(timeout = Duration.ofMillis(EXIT_WAIT_MS)) }
                .isInstanceOf(TimeoutException::class.java)
                .hasMessageContaining("did not exit")
        }
    }

    @Test
    fun `closing a session without an exit assertion only releases resources`() {
        val server = SessionTestServer()
        inProcessSession(server).use { }
        assertThat(server.exitCalls).isZero()
        assertThat(server.shutdownCalls).isEqualTo(1)
    }

    @Test
    fun `an interrupted scenario releases resources before restoring interruption`() {
        var cleaned = false
        val resources = SessionResources().apply {
            own(
                AutoCloseable {
                    assertThat(Thread.currentThread().isInterrupted).isFalse()
                    cleaned = true
                },
            )
        }
        val session = processSession(ControlledProcess(0), resources)

        Thread.currentThread().interrupt()
        try {
            session.close()
            assertThat(cleaned).isTrue()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interruption suppressed by an earlier cleanup failure is restored without replacing that failure`() {
        val failure = IOException("primary cleanup failure")
        val interruption = InterruptedException("interrupted later cleanup")
        val resources = SessionResources().apply {
            own(AutoCloseable { throw interruption })
            own(AutoCloseable { throw failure })
        }

        try {
            assertThatThrownBy { processSession(ControlledProcess(0), resources).close() }.isSameAs(failure)
            assertThat(failure.suppressed).containsExactly(interruption)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    private fun inProcessSession(server: SessionTestServer): LanguageServerSession =
        LanguageServerSessionFactory(createServer = { onExit -> server.also { it.onExit = onExit } })
            .start(ServerConfig(mode = ServerLaunchMode.InProcess), "exit-outcome")

    private fun processSession(
        process: Process,
        resources: SessionResources = SessionResources(),
    ): LanguageServerSession = LanguageServerSession(
        process = process,
        server = SessionTestServer(),
        client = HarnessLanguageClient(),
        endpoint = RemoteEndpoint(
            {},
            object : Endpoint {
                override fun request(method: String, parameter: Any?): CompletableFuture<*> =
                    CompletableFuture.completedFuture(null)

                override fun notify(method: String, parameter: Any?) = Unit
            },
        ),
        resources = resources,
    )

    private class ControlledProcess(private val code: Int, private val completed: Boolean = true) : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = completed
        override fun exitValue(): Int = code
        override fun isAlive(): Boolean = !completed
        override fun destroy() = Unit
    }

    private companion object {
        const val EXIT_WAIT_MS = 100L
        const val EXIT_TIMEOUT_SECONDS = 5L
        const val NONZERO_EXIT = 7
    }
}
