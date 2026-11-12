package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

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
    fun `known failure cannot hide independent cleanup failure`() {
        every { WorkspaceFixture.cleanup(workspace) } throws IOException("cleanup failed")
        val checks = listOf(JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known", "missing")))

        val outcome = ScenarioExecutor(factory).executeWithOutcome(definition(checks))

        assertThat(outcome.observed).containsExactly(ObservationResult("known", ObservationStatus.KnownFailure(42)))
        assertThat(outcome.cleanup).isInstanceOf(CleanupOutcome.Failed::class.java)
        assertThat(outcome.isSuccessful).isFalse()
    }

    private fun definition(checks: List<JsonCheck>) = ScenarioDefinition(
        Scenario(name = "named", steps = listOf(ScenarioStep.Assert(source = "workspace", checks = checks))),
        source = tempDir.resolve("scenario.yaml").toString(),
    )
}
