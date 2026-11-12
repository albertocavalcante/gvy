// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

@Isolated
class NamedHighLevelChecksTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `high level request checks retain independent named outcomes`() {
        val checks = listOf(
            JsonCheck("$.missing", JsonExpectation(), id = "known", xfail = Xfail(42, "known", "missing")),
            JsonCheck("$.present", JsonExpectation(), id = "later"),
        )
        val steps = listOf(
            ScenarioStep.Completion(uri = "file:///example.groovy", line = 0, character = 0, checks = checks),
            ScenarioStep.CodeAction(uri = "file:///example.groovy", checks = checks),
            ScenarioStep.Formatting(uri = "file:///example.groovy", checks = checks),
            ScenarioStep.Rename(
                uri = "file:///example.groovy",
                line = 0,
                character = 0,
                newName = "n",
                checks = checks,
            ),
        )
        val session = mockk<LanguageServerSession>(relaxed = true)
        val factory = mockk<LanguageServerSessionFactory>()
        every { factory.start(any(), any()) } returns session
        every { session.endpoint.request(any(), any()) } returns
            CompletableFuture.completedFuture(mapOf("present" to true))
        mockkObject(WorkspaceFixture)
        try {
            steps.forEachIndexed { index, step ->
                val workspace = Files.createDirectory(tempDir.resolve("workspace-$index"))
                every { WorkspaceFixture.materialize(any(), any()) } returns workspace
                every { WorkspaceFixture.cleanup(workspace) } answers { callOriginal() }
                val definition = ScenarioDefinition(
                    Scenario(name = "high-level-$index", steps = listOf(step)),
                    source = tempDir.resolve("scenario.yaml").toString(),
                )

                val outcome = ScenarioExecutor(factory).executeWithOutcome(definition)

                assertThat(outcome.observed).describedAs(step.toString()).containsExactly(
                    ObservationResult("known", ObservationStatus.KnownFailure(42)),
                    ObservationResult("later", ObservationStatus.Passed),
                )
                assertThat(outcome.blocked).isEmpty()
                assertThat(outcome.cleanup).isEqualTo(CleanupOutcome.Succeeded)
            }
        } finally {
            unmockkObject(WorkspaceFixture)
        }
    }
}
