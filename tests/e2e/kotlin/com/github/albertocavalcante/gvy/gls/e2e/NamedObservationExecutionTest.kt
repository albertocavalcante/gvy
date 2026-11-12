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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Isolated
class NamedObservationExecutionTest {
    @TempDir lateinit var tempDir: Path
    private lateinit var workspace: Path
    private val factory = mockk<LanguageServerSessionFactory>()

    @BeforeEach
    fun setup() {
        workspace = Files.createDirectory(tempDir.resolve("workspace"))
        mockkObject(WorkspaceFixture)
        every { WorkspaceFixture.materialize(any(), any()) } returns workspace
        every { WorkspaceFixture.cleanup(workspace) } answers { callOriginal() }
    }

    @AfterEach fun teardown() = unmockkObject(WorkspaceFixture)

    @Test
    fun `known failing observation does not prevent later independent check`() {
        val checks = listOf(
            JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known", "missing")),
            JsonCheck("$.uri", JsonExpectation(), id = "later"),
        )
        val outcome = ScenarioExecutor(factory).executeWithOutcome(definition(checks))

        assertThat(outcome.observed).containsExactly(
            ObservationResult("known", ObservationStatus.KnownFailure(42)),
            ObservationResult("later", ObservationStatus.Passed),
        )
        assertThat(outcome.blocked).isEmpty()
        assertThat(outcome.cleanup).isEqualTo(CleanupOutcome.Succeeded)
    }

    @Test
    fun `same message from a different observation is not accepted as known failure`() {
        val checks = listOf(
            JsonCheck("$.missing", JsonExpectation(), id = "wrong"),
            JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known", "missing")),
        )
        val outcome = ScenarioExecutor(factory).executeWithOutcome(definition(checks))

        assertThat(outcome.isSuccessful).isFalse()
        assertThat(outcome.cleanup).isEqualTo(CleanupOutcome.Succeeded)
        assertThat(outcome.observed).anySatisfy { result ->
            assertThat(result.id).isEqualTo("wrong")
            assertThat(result.status).isInstanceOf(ObservationStatus.Failed::class.java)
        }
    }

    @Test
    fun `mismatched known failure remains failed and later checks are blocked`() {
        val checks = listOf(
            JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known", "different")),
            JsonCheck("$.uri", JsonExpectation(), id = "later"),
        )

        val outcome = ScenarioExecutor(factory).executeWithOutcome(definition(checks))

        assertThat(outcome.observed).hasSize(1)
        assertThat(outcome.observed.single().status).isInstanceOf(ObservationStatus.Failed::class.java)
        assertThat(outcome.blocked).containsExactly("later")
        assertThat(outcome.isSuccessful).isFalse()
    }

    @Test
    fun `legacy execution rejects per-check metadata before creating workspace`() {
        val checks = listOf(JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known")))

        assertThatThrownBy { ScenarioExecutor(factory).execute(definition(checks)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { WorkspaceFixture.materialize(any(), any()) }
    }

    @Test
    fun `duplicate IDs are rejected before creating a workspace`() {
        val checks = listOf(
            JsonCheck("$.uri", JsonExpectation(), id = "duplicate"),
            JsonCheck("$.path", JsonExpectation(), id = "duplicate"),
        )

        assertThatThrownBy { ScenarioExecutor(factory).executeWithOutcome(definition(checks)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { WorkspaceFixture.materialize(any(), any()) }
    }

    @Test
    fun `unsupported named notification check is rejected before creating a workspace`() {
        val scenario = Scenario(
            name = "unsupported",
            steps = listOf(
                ScenarioStep.WaitNotification(
                    "example",
                    checks = listOf(
                        JsonCheck("$", JsonExpectation(), id = "notification"),
                    ),
                ),
            ),
        )

        assertThatThrownBy { ScenarioExecutor(factory).executeWithOutcome(ScenarioDefinition(scenario)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { WorkspaceFixture.materialize(any(), any()) }
    }

    @Test
    fun `shared execution and cleanup throwable keeps both failure origins`() {
        val shared = IOException("shared transport and cleanup failure")
        every { factory.start(any(), any()) } throws shared
        every { WorkspaceFixture.cleanup(workspace) } throws shared
        val scenario = Scenario(name = "shared failure", steps = listOf(ScenarioStep.Initialized))

        val outcome = ScenarioExecutor(factory).executeWithOutcome(ScenarioDefinition(scenario))

        val completion = outcome.completion as ScenarioCompletion.Failed
        assertThat(completion.detail).contains("shared transport and cleanup failure")
        val cleanup = outcome.cleanup as CleanupOutcome.Failed
        assertThat(cleanup.detail).contains("shared transport and cleanup failure")
        assertThat(cleanup.confirmed).isTrue()
    }

    @Test
    fun `deadline seals incomplete report while a late worker is still running`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val session = mockk<LanguageServerSession>(relaxed = true)
        every { factory.start(any(), any()) } answers {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model a startup that has not responded to cancellation yet.
                }
            }
            session
        }
        every { WorkspaceFixture.cleanup(workspace) } answers {
            try {
                callOriginal()
            } finally {
                cleaned.countDown()
            }
        }
        val executor = ScenarioExecutor(factory) {
            ScenarioExecution(
                Duration.ofSeconds(5),
                cleanupTimeout = Duration.ofMillis(100),
                awaitOutcome = { _, _ ->
                    check(entered.await(5, TimeUnit.SECONDS))
                    throw TimeoutException("controlled deadline")
                },
            )
        }
        val scenario = Scenario(name = "late worker", steps = listOf(
            ScenarioStep.Initialized,
            ScenarioStep.Assert("workspace", listOf(JsonCheck("$.uri", JsonExpectation(), id = "later"))),
        ))
        try {
            val outcome = executor.executeWithOutcome(ScenarioDefinition(scenario))
            assertThat(outcome.blocked).containsExactly("later")
            val cleanup = outcome.cleanup as CleanupOutcome.Failed
            assertThat(cleanup.confirmed).isFalse()
            assertThat(cleanup.detail).contains("cleanup")
            release.countDown()
            assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(outcome.observed).isEmpty()
            assertThat(outcome.blocked).containsExactly("later")
        } finally {
            release.countDown()
            cleaned.await(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `empty plan still reports actual successful cleanup`() {
        val outcome = ScenarioExecutor(factory).executeWithOutcome(definition(emptyList()))

        assertThat(outcome.counts.planned).isZero()
        assertThat(outcome.observed).isEmpty()
        assertThat(outcome.completion).isEqualTo(ScenarioCompletion.Completed)
        assertThat(outcome.cleanup).isEqualTo(CleanupOutcome.Succeeded)
    }

    @Test
    fun `known failure cannot hide independent cleanup failure`() {
        every { WorkspaceFixture.cleanup(workspace) } throws IOException("cleanup failed")
        val checks =
            listOf(JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known", "missing")))

        val outcome = ScenarioExecutor(factory).executeWithOutcome(definition(checks))

        assertThat(outcome.observed).containsExactly(ObservationResult("known", ObservationStatus.KnownFailure(42)))
        assertThat(outcome.cleanup).isInstanceOf(CleanupOutcome.Failed::class.java)
        assertThat((outcome.cleanup as CleanupOutcome.Failed).confirmed).isTrue()
        assertThat(outcome.isSuccessful).isFalse()
    }

    private fun definition(checks: List<JsonCheck>) = ScenarioDefinition(
        Scenario(name = "named", steps = listOf(ScenarioStep.Assert(source = "workspace", checks = checks))),
        source = tempDir.resolve("scenario.yaml").toString(),
    )
}
