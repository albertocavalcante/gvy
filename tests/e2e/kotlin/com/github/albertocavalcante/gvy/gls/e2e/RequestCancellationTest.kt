package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.api.TestSuite
import com.jayway.jsonpath.Configuration
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.InitializeResult
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RequestCancellationTest {
    @Test
    fun `a timed out request cancels its pending RPC future`() {
        val pending = CompletableFuture<Any>()
        val session = mockk<LanguageServerSession>(relaxed = true)
        val context = context(session)
        every { session.endpoint.request("test/never", any()) } returns pending

        assertThatThrownBy {
            SendRequestStepExecutor().execute(ScenarioStep.SendRequest(method = "test/never", timeoutMs = 1), context)
        }.isInstanceOf(TimeoutException::class.java)

        assertThat(pending.isCancelled).isTrue()
    }

    @Test
    fun `a timed out typed custom request also cancels its pending RPC future`() {
        val pending = CompletableFuture<List<TestSuite>>()
        val session = mockk<LanguageServerSession>(relaxed = true)
        val context = context(session)
        every { session.groovyServer.discoverTests(any()) } returns pending

        assertThatThrownBy {
            SendRequestStepExecutor().execute(
                ScenarioStep.SendRequest(
                    method = "groovy/discoverTests",
                    params = buildJsonObject { put("workspaceUri", "file:///workspace") },
                    timeoutMs = 1,
                ),
                context,
            )
        }.isInstanceOf(TimeoutException::class.java)

        assertThat(pending.isCancelled).isTrue()
    }

    @Test
    fun `cancellation immediately after initialize starts cannot strand its pending RPC`() {
        val pending = CompletableFuture<InitializeResult>()
        val session = mockk<LanguageServerSession>(relaxed = true)
        every { session.server.initialize(any()) } returns pending
        val lookups = AtomicInteger()
        val context = ScenarioContext(
            definition = ScenarioDefinition(Scenario("cancel-initialize", steps = emptyList()), "scenario.yaml"),
            sessionAccess = ScenarioSessionAccess(
                session = {
                    if (lookups.incrementAndGet() > 1) throw InterruptedException("cancelled during session lookup")
                    session
                },
                checkActive = { throw InterruptedException("cancelled immediately after request creation") },
            ),
            workspace = ScenarioWorkspace(Path.of(".")),
            jsonPathConfig = Configuration.defaultConfiguration(),
        )

        assertThatThrownBy { InitializeStepExecutor().execute(ScenarioStep.Initialize(), context) }
            .isInstanceOf(InterruptedException::class.java)

        assertThat(pending.isCancelled).isTrue()
        assertThat(lookups).hasValue(1)
    }

    @Test
    fun `a premature server exit cancels initialization and reports the observed status`() {
        val pending = CompletableFuture<InitializeResult>()
        val session = mockk<LanguageServerSession>(relaxed = true)
        val process = mockk<Process>()
        every { process.isAlive } returns false
        every { process.exitValue() } returns FAILED_EXIT_CODE
        every { session.process } returns process
        every { session.server.initialize(any()) } returns pending

        assertThatThrownBy { InitializeStepExecutor().execute(ScenarioStep.Initialize(), context(session)) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("code $FAILED_EXIT_CODE")

        assertThat(pending.isCancelled).isTrue()
    }

    private fun context(session: LanguageServerSession) = ScenarioContext(
        definition = ScenarioDefinition(Scenario("request-cancellation", steps = emptyList()), "scenario.yaml"),
        session = session,
        workspace = ScenarioWorkspace(Path.of(".")),
        jsonPathConfig = Configuration.defaultConfiguration(),
    )

    private companion object {
        const val FAILED_EXIT_CODE = 7
    }
}
