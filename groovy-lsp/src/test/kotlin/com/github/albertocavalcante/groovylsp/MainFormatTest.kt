package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.albertocavalcante.groovylsp.cli.CheckCommand
import com.github.albertocavalcante.groovylsp.cli.ExecuteCommand
import com.github.albertocavalcante.groovylsp.cli.FormatCommand
import com.github.albertocavalcante.groovylsp.cli.GlsCommand
import com.github.albertocavalcante.groovylsp.cli.LspCommand
import com.github.albertocavalcante.groovylsp.cli.VersionCommand
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class MainFormatTest {

    /**
     * Helper to run a subcommand through the root GlsCommand,
     * ensuring proper context setup (e.g., Terminal object).
     */
    private fun runWithContext(vararg args: String) {
        GlsCommand()
            .subcommands(
                LspCommand(),
                FormatCommand(),
                CheckCommand(),
                ExecuteCommand(),
                VersionCommand(),
            )
            .parse(args.toList())
    }

    @Test
    fun `format prints formatted content for a file`() {
        val tempFile = File.createTempFile("Format", ".groovy")
        tempFile.writeText("class A{ def x(){  println 'hi' } }")
        tempFile.deleteOnExit()

        val output = captureOutput {
            runWithContext("format", tempFile.absolutePath)
        }

        assertTrue(output.contains("class A"), "Expected formatted output to include class declaration, got: $output")
    }

    @Test
    fun `format throws for missing files`() {
        // Clikt will throw an exception for non-existent files when mustExist = true
        // Use CliktError as the base type since the specific exception type may vary
        assertThrows<CliktError> {
            runWithContext("format", "does-not-exist.groovy")
        }
    }

    /**
     * Captures stdout output during the execution of a block.
     */
    private fun captureOutput(block: () -> Unit): String {
        val originalOut = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(originalOut)
        }
        return baos.toString()
    }
}
