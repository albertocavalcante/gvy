package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceFixtureTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a missing fixture is rejected before allocating a workspace`() {
        val workspace = tempDir.resolve("allocated")
        var allocated = false

        assertThatThrownBy {
            WorkspaceFixture.materialize(tempDir.resolve("scenario.yaml"), "does-not-exist", {
                allocated = true
                Files.createDirectories(workspace)
            })
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(allocated).isFalse()
        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `a copy failure removes the partially materialized workspace and retains the original error`() {
        Files.createDirectories(tempDir.resolve("workspaces/example"))
        val workspace = tempDir.resolve("allocated")
        val failure = IOException("fixture copy failed")

        assertThatThrownBy {
            WorkspaceFixture.materialize(
                tempDir.resolve("scenario.yaml"),
                "example",
                { Files.createDirectories(workspace) },
            ) { _, target ->
                Files.writeString(target.resolve("partial.txt"), "partial")
                throw failure
            }
        }.isSameAs(failure)

        assertThat(workspace).doesNotExist()
    }

    @Test
    fun `a successful copy retains the workspace until explicit cleanup`() {
        val fixture = Files.createDirectories(tempDir.resolve("workspaces/example"))
        Files.writeString(fixture.resolve("Example.groovy"), "class Example {}")
        val workspace = tempDir.resolve("allocated")

        val result = WorkspaceFixture.materialize(
            tempDir.resolve("scenario.yaml"),
            "example",
            { Files.createDirectories(workspace) },
        )

        assertThat(result).isEqualTo(workspace)
        assertThat(Files.readString(workspace.resolve("Example.groovy"))).isEqualTo("class Example {}")
        WorkspaceFixture.cleanup(workspace)
        assertThat(workspace).doesNotExist()
    }
}
