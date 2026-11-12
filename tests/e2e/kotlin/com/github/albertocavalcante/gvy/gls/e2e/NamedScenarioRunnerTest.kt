package com.github.albertocavalcante.gvy.gls.e2e

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path

@Isolated
class NamedScenarioRunnerTest {
    @TempDir lateinit var tempDir: Path

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
        } finally {
            unmockkObject(WorkspaceFixture)
        }
    }
}
