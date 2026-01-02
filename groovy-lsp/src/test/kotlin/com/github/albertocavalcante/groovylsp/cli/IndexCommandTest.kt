package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.albertocavalcante.groovylsp.indexing.IndexFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IndexCommandTest {

    @Test
    fun `command has correct name`() {
        // This will fail to compile as IndexCommand does not exist yet
        val command = IndexCommand()
        assertThat(command.commandName).isEqualTo("index")
    }

    @Test
    fun `generates scip index`(@TempDir tempDir: Path) {
        val srcFile = tempDir.resolve("Test.groovy")
        Files.writeString(srcFile, "class Test {}")

        val outFile = tempDir.resolve("index.scip")

        val command = IndexCommand()
        // Using main to parse args and run
        command.main(
            listOf(
                "--format",
                "SCIP",
                "--output",
                outFile.toAbsolutePath().toString(),
                tempDir.toAbsolutePath().toString(),
            ),
        )

        assertThat(outFile).exists()
        assertThat(Files.size(outFile)).isGreaterThan(0)
    }

    @Test
    fun `generates lsif index`(@TempDir tempDir: Path) {
        val srcFile = tempDir.resolve("Test.groovy")
        Files.writeString(srcFile, "class Test {}")

        val outFile = tempDir.resolve("index.lsif")

        val command = IndexCommand()
        command.main(
            listOf(
                "--format",
                "LSIF",
                "--output",
                outFile.toAbsolutePath().toString(),
                tempDir.toAbsolutePath().toString(),
            ),
        )

        assertThat(outFile).exists()
        // LSIF is JSON, should be readable
        val content = Files.readString(outFile)
        assertThat(content).contains("vertex")
    }
}
