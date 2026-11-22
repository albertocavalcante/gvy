// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path

@Isolated
class NamedScenarioRunnerTest {
    @TempDir lateinit var tempDir: Path
    private var previousReportDirectory: String? = null

    @BeforeEach
    fun isolateSyntheticReports() {
        previousReportDirectory = System.getProperty(ScenarioOutcomeReport.DIRECTORY_PROPERTY)
        System.setProperty(ScenarioOutcomeReport.DIRECTORY_PROPERTY, tempDir.resolve("reports").toString())
    }

    @AfterEach
    fun restoreReportDirectory() {
        val previous = previousReportDirectory
        if (previous == null) {
            System.clearProperty(ScenarioOutcomeReport.DIRECTORY_PROPERTY)
        } else {
            System.setProperty(ScenarioOutcomeReport.DIRECTORY_PROPERTY, previous)
        }
    }

    @Test
    fun `real runner path reports later unexpected pass after earlier known failure`() {
        val workspace = Files.createDirectory(tempDir.resolve("workspace"))
        mockkObject(WorkspaceFixture)
        try {
            every { WorkspaceFixture.materialize(any(), any()) } returns workspace
            every { WorkspaceFixture.cleanup(workspace) } answers { callOriginal() }
            val yaml = """
                name: named-runner
                steps:
                  - assert:
                      source: workspace
                      checks:
                        - jsonPath: '$.missing'
                          id: known
                          xfail: { issue: 42, reason: known, match: missing }
                          expect: { type: EXISTS }
                        - jsonPath: '$.uri'
                          id: later
                          xfail: { issue: 43, reason: stale }
                          expect: { type: EXISTS }
            """.trimIndent()
            val definition = JacksonScenarioParser().parseScenarioDefinition(yaml)
                .copy(source = tempDir.resolve("scenario.yaml").toString())
            val executor = ScenarioExecutor(mockk())

            assertThatThrownBy { ScenarioRunnerExecution.run(definition, executor) }
                .isInstanceOf(AssertionError::class.java).hasMessageContaining("later")
            Files.list(tempDir.resolve("reports")).use { reports ->
                assertThat(reports.toList()).hasSize(1)
            }
        } finally {
            unmockkObject(WorkspaceFixture)
        }
    }
}
