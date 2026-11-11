package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.InitializeParams
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class LanguageServerSessionFactoryTest {
    private val server = SessionTestServer()

    @Test
    fun `closing an in-process session stops both listener threads and shuts down the server`() {
        val threadsBefore = Thread.getAllStackTraces().keys
        val factory = LanguageServerSessionFactory(createServer = { server })
        val session = factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "listener-ownership")
        var listeners: Set<Thread> = emptySet()

        try {
            // A round trip establishes both readers, independently of executor scheduling.
            session.server.initialize(InitializeParams()).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            listeners = Thread.getAllStackTraces().filter { (thread, stack) ->
                thread !in threadsBefore && stack.any { it.className.endsWith("StreamMessageProducer") }
            }.keys
            assertThat(listeners).hasSize(2)
            session.close()
            listeners.forEach { it.join(LISTENER_JOIN_MS) }

            assertThat(listeners.filter(Thread::isAlive)).isEmpty()
            assertThat(server.shutdownCalls).isEqualTo(1)
            session.close()
            assertThat(server.shutdownCalls).isEqualTo(1)
        } finally {
            session.close()
            // Keep the failing baseline regression from leaving its leaked reader alive.
            listeners.forEach(Thread::interrupt)
        }
    }

    @Test
    fun `a subprocess receives scenario arguments and explicit environment overrides`() {
        var observedCommand: List<String>? = null
        var observedEnvironment: Map<String, String>? = null
        val factory = LanguageServerSessionFactory(startProcess = { builder ->
            observedCommand = builder.command().toList()
            observedEnvironment = builder.environment().toMap()
            throw IOException("stop before starting the test process")
        })

        assertThatThrownBy {
            factory.start(
                ServerConfig(
                    args = listOf("--log-level", "debug"),
                    env = mapOf("GVY_HARNESS_TEST" to "authored-value", "GRADLE_USER_HOME" to "authored-gradle-home"),
                ),
                "launch-configuration",
            )
        }.isInstanceOf(IOException::class.java)

        assertThat(observedCommand?.takeLast(3)).containsExactly("stdio", "--log-level", "debug")
        assertThat(observedEnvironment).containsEntry("GVY_HARNESS_TEST", "authored-value")
        assertThat(observedEnvironment).containsEntry("GRADLE_USER_HOME", "authored-gradle-home")
    }

    @Test
    fun `in-process mode rejects process arguments before constructing a server`() {
        val factory = LanguageServerSessionFactory(createServer = { server })

        assertThatThrownBy {
            factory.start(ServerConfig(mode = ServerLaunchMode.InProcess, args = listOf("--debug")), "bad-args")
                .use { }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("args")
        assertThat(server.connectCalls).isZero()
    }

    @Test
    fun `in-process mode rejects environment overrides before constructing a server`() {
        val factory = LanguageServerSessionFactory(createServer = { server })

        assertThatThrownBy {
            factory.start(ServerConfig(mode = ServerLaunchMode.InProcess, env = mapOf("A" to "b")), "bad-env")
                .use { }
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("env")
        assertThat(server.connectCalls).isZero()
    }

    @Test
    fun `a failed in-process connection shuts down the partially constructed server`() {
        val failure = IllegalStateException("connection failed")
        server.connectFailure = failure
        val factory = LanguageServerSessionFactory(createServer = { server })

        assertThatThrownBy {
            factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "failed-connection")
        }.isSameAs(failure)

        assertThat(server.shutdownCalls).isEqualTo(1)
    }

    @Test
    fun `a failed server shutdown still retires its listeners and executor`() {
        val failure = IOException("server shutdown failed")
        server.shutdownResult = CompletableFuture.failedFuture(failure)
        val scenarioName = "failed-server-shutdown"
        val session = LanguageServerSessionFactory(createServer = { server })
            .start(ServerConfig(mode = ServerLaunchMode.InProcess), scenarioName)

        assertThatThrownBy { session.close() }.isInstanceOf(ExecutionException::class.java).hasCause(failure)

        assertThat(Thread.getAllStackTraces().keys.filter { it.name == "groovy-lsp-e2e-$scenarioName" }).isEmpty()
    }

    private companion object {
        const val LISTENER_JOIN_MS = 1_000L
        const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
