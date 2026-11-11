package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Isolated
class ScenarioExecutionTest {
    @TempDir
    lateinit var tempDir: Path
    private val factory = mockk<LanguageServerSessionFactory>()
    private val session = mockk<LanguageServerSession>(relaxed = true)
    private lateinit var workspace: Path
    private var oldTimeout: String? = null

    @BeforeEach
    fun setUp() {
        workspace = Files.createDirectory(tempDir.resolve("workspace"))
        mockkObject(WorkspaceFixture)
        every { WorkspaceFixture.materialize(any(), any()) } returns workspace
        every { WorkspaceFixture.cleanup(workspace) } answers { callOriginal() }
        every { factory.start(any(), any()) } returns session
        oldTimeout = System.getProperty(TIMEOUT_PROPERTY)
    }

    @AfterEach
    fun restore() {
        if (oldTimeout == null) {
            System.clearProperty(TIMEOUT_PROPERTY)
        } else {
            System.setProperty(TIMEOUT_PROPERTY, oldTimeout)
        }
        unmockkObject(WorkspaceFixture)
    }

    @Test
    fun `an empty scenario never launches a language server`() {
        ScenarioExecutor(factory).execute(definition(emptyList()))

        verify(exactly = 0) { factory.start(any(), any()) }
        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `an explicitly blank deadline fails before acquiring a workspace or server`() {
        System.setProperty(TIMEOUT_PROPERTY, "")

        assertThatThrownBy { ScenarioExecutor(factory).execute(definition(emptyList())) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(TIMEOUT_PROPERTY)

        verify(exactly = 0) { WorkspaceFixture.materialize(any(), any()) }
        verify(exactly = 0) { factory.start(any(), any()) }
    }

    @Test
    fun `multiple LSP steps share the single lazily acquired session`() {
        ScenarioExecutor(factory).execute(definition(listOf(ScenarioStep.Initialized, ScenarioStep.Initialized)))

        verify(exactly = 1) { factory.start(any(), any()) }
        verify(exactly = 1) { session.close() }
    }

    @Test
    fun `the deadline includes workspace preparation and closes a late workspace`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        every { WorkspaceFixture.materialize(any(), any()) } answers {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                cancelled.countDown()
            }
            workspace
        }

        assertThatThrownBy { expiringAfter(entered).execute(definition(emptyList())) }
            .isInstanceOf(ScenarioDeadlineExceeded::class.java)

        assertThat(cancelled.count).isZero()
        assertThat(workspace).doesNotExist()
        verify(exactly = 0) { factory.start(any(), any()) }
    }

    @Test
    fun `a CLI only scenario runs without launching a language server`() {
        ScenarioExecutor(factory).execute(
            definition(listOf(ScenarioStep.CliCommand(resolveJavaBinary(), args = listOf("-Xmx32m", "-version")))),
        )

        verify(exactly = 0) { factory.start(any(), any()) }
        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `the whole scenario deadline interrupts a blocked request and cancels it`() {
        val entered = CountDownLatch(1)
        val pending = CompletableFuture<Any>()
        every { session.endpoint.request("test/blocked", any()) } answers {
            entered.countDown()
            pending
        }

        assertThatThrownBy {
            expiringAfter(entered).execute(
                definition(listOf(ScenarioStep.SendRequest(method = "test/blocked"))),
            )
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("whole-scenario deadline")

        assertThat(pending.isCancelled).isTrue()
        verify(exactly = 1) { session.close() }
        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `a server returned after deadline cancellation is closed without executing a request`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        every { factory.start(any(), any()) } answers {
            entered.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                // Model a launcher which completes acquisition after consuming the interrupt.
                cancelled.countDown()
            }
            session
        }

        assertThatThrownBy {
            expiringAfter(entered).execute(definition(listOf(ScenarioStep.SendRequest(method = "test/late"))))
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("whole-scenario deadline")

        assertThat(entered.count).isZero()
        assertThat(cancelled.count).isZero()
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { session.endpoint.request(any(), any()) }
        assertThat(workspace).doesNotExist()
    }

    private fun definition(steps: List<ScenarioStep>) = ScenarioDefinition(
        Scenario(name = "execution-boundaries", steps = steps),
        source = tempDir.resolve("scenario.yaml").toString(),
    )

    private fun expiringAfter(entered: CountDownLatch) = ScenarioExecutor(factory) { timeout ->
        ScenarioExecution(
            timeout,
            cleanupTimeout = Duration.ofSeconds(FIXTURE_WAIT_SECONDS),
            awaitOutcome = { _, _ ->
                check(entered.await(FIXTURE_WAIT_SECONDS, TimeUnit.SECONDS)) { "Fixture branch did not start" }
                throw TimeoutException("Controlled expiration after branch entry")
            },
        )
    }

    companion object {
        private const val TIMEOUT_PROPERTY = "groovy.lsp.e2e.scenarioTimeoutMs"
        private const val FIXTURE_WAIT_SECONDS = 2L
    }
}
