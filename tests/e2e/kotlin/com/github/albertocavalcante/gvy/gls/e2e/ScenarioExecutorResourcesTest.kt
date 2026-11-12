// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Isolated
class ScenarioExecutorResourcesTest {
    @TempDir
    lateinit var tempDir: Path
    private lateinit var workspace: Path
    private val session = mockk<LanguageServerSession>(relaxed = true)
    private val factory = mockk<LanguageServerSessionFactory>()

    @BeforeEach
    fun prepareWorkspace() {
        workspace = Files.createDirectory(tempDir.resolve("workspace"))
        mockkObject(WorkspaceFixture)
        every { WorkspaceFixture.materialize(any(), any()) } returns workspace
        every { WorkspaceFixture.cleanup(workspace) } answers { callOriginal() }
        every { factory.start(any(), any()) } returns session
    }

    @AfterEach
    fun restoreWorkspaceFixture() {
        unmockkObject(WorkspaceFixture)
    }

    @Test
    fun `a failed session launch still deletes the materialized workspace`() {
        val failure = IOException("session launch failed")
        every { factory.start(any(), any()) } throws failure

        assertThatThrownBy { ScenarioExecutor(factory).execute(definition()) }.isSameAs(failure)

        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `a failed session close still deletes the materialized workspace`() {
        val failure = IOException("session close failed")
        every { session.close() } throws failure

        assertThatThrownBy { ScenarioExecutor(factory).execute(definition()) }.isSameAs(failure)

        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `a scenario failure remains primary when session and workspace cleanup both fail`() {
        val sessionFailure = IOException("session cleanup failed")
        val workspaceFailure = IOException("workspace cleanup failed")
        every { session.close() } throws sessionFailure
        every { WorkspaceFixture.cleanup(workspace) } throws workspaceFailure

        val observed = checkNotNull(
            catchThrowable {
                ScenarioExecutor(
                    factory,
                ).execute(definition(listOf(ScenarioStep.Assert(source = "missing", checks = emptyList()))))
            },
        )

        assertThat(
            observed,
        ).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Unknown result or variable")
        assertThat(observed.suppressed).containsExactly(sessionFailure, workspaceFailure)
        verify(exactly = 1) { session.close() }
        verify(exactly = 1) { WorkspaceFixture.cleanup(workspace) }
    }

    @Test
    fun `xfail does not hide cleanup failure after a matching scenario assertion`() {
        val cleanupFailure = IOException("session cleanup failed")
        every { session.close() } throws cleanupFailure
        val step = ScenarioStep.Assert(
            source = "workspace",
            checks = listOf(JsonCheck("$.missing", JsonExpectation())),
        )

        val observed = checkNotNull(
            catchThrowable {
                XfailOutcome.run(Xfail(issue = 42, reason = "known bug", match = "missing"), "scenario.yaml") {
                    ScenarioExecutor(factory).execute(definition(listOf(step)))
                }
            },
        )

        assertThat(observed).isInstanceOf(AssertionError::class.java).hasMessageContaining("missing")
        assertThat(observed.suppressed).containsExactly(cleanupFailure)
        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `an invalid inline file remains the primary error when rollback cleanup fails`() {
        val cleanupFailure = IOException("rollback cleanup failed")
        every { WorkspaceFixture.cleanup(workspace) } throws cleanupFailure

        val observed = checkNotNull(
            catchThrowable {
                writeInlineFilesOrCleanUp(workspace, "invalid-inline", mapOf("../outside.groovy" to "x"))
            },
        )

        assertThat(observed).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("invalid-inline")
        assertThat(observed.suppressed).containsExactly(cleanupFailure)
    }

    @Test
    fun `a successful scenario keeps its workspace during launch then closes both resources`() {
        every { factory.start(any(), any()) } answers {
            assertThat(workspace).exists()
            session
        }

        ScenarioExecutor(factory).execute(definition())

        verify(exactly = 1) { session.close() }
        assertThat(workspace).doesNotExist()
    }

    private fun definition(steps: List<ScenarioStep> = emptyList()): ScenarioDefinition = ScenarioDefinition(
        Scenario(name = "resource-scope", steps = listOf(ScenarioStep.Initialized) + steps),
        source = tempDir.resolve("scenario.yaml").toString(),
    )
}
