// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.services.GenericError
import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.gvy.gls.services.Health
import com.github.albertocavalcante.gvy.gls.services.StatusNotification
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
    fun `factory transports typed status details and signals readiness`() {
        val factory = LanguageServerSessionFactory(createServer = { server })
        factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "status-wire").use { session ->
            val remoteClient = server.connectedClient as GroovyLanguageClient
            val warning = StatusNotification(
                Health.Warning,
                errorDetails = GenericError("DEPENDENCY_RESOLUTION_FAILED", mapOf("host" to "repository")),
            )
            remoteClient.groovyStatus(warning)
            val received = session.client.awaitNotificationDetailed("groovy/status", 5_000, { it == warning })
            assertThat(received.envelope?.payload).isEqualTo(warning)
            remoteClient.groovyStatus(StatusNotification())
            session.client.readyFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `factory propagates error health to readiness failure`() {
        LanguageServerSessionFactory(createServer = { server })
            .start(ServerConfig(mode = ServerLaunchMode.InProcess), "status-error-wire").use { session ->
                val remoteClient = server.connectedClient as GroovyLanguageClient
                remoteClient.groovyStatus(StatusNotification(Health.Error, message = "Build failed"))
                assertThatThrownBy { session.client.readyFuture.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    .isInstanceOf(ExecutionException::class.java)
                    .hasMessageContaining("Build failed")
            }
    }

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
