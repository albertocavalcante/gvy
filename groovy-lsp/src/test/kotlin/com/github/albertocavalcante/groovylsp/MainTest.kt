package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.albertocavalcante.groovylsp.cli.CheckCommand
import com.github.albertocavalcante.groovylsp.cli.ExecuteCommand
import com.github.albertocavalcante.groovylsp.cli.FormatCommand
import com.github.albertocavalcante.groovylsp.cli.GlsCommand
import com.github.albertocavalcante.groovylsp.cli.LspCommand
import com.github.albertocavalcante.groovylsp.cli.VersionCommand
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class MainTest {

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
            .main(args.toList())
    }

    @Test
    fun `test version command outputs version string`() {
        val output = captureOutput {
            runWithContext("version")
        }
        assertTrue(output.contains("gls") && output.contains("version"))
    }

    @Test
    fun `test help is available on root command`() {
        val command = GlsCommand()
            .subcommands(
                LspCommand(),
                FormatCommand(),
                CheckCommand(),
                ExecuteCommand(),
                VersionCommand(),
            )

        // Verify the command structure is valid
        assertTrue(command.registeredSubcommandNames().contains("version"))
        assertTrue(command.registeredSubcommandNames().contains("format"))
        assertTrue(command.registeredSubcommandNames().contains("check"))
        assertTrue(command.registeredSubcommandNames().contains("lsp"))
        assertTrue(command.registeredSubcommandNames().contains("execute"))
    }

    @Test
    fun `test format command formats groovy file`(@TempDir tempDir: File) {
        val tempFile = File(tempDir, "Test.groovy")
        tempFile.writeText("class Test { void foo() { println 'bar' } }")

        val output = captureOutput {
            runWithContext("format", tempFile.absolutePath)
        }

        // The formatter should produce some output (the formatted file content)
        assertTrue(output.isNotEmpty(), "Expected formatted output, got empty string")
    }

    @Test
    fun `test check command checks groovy file`(@TempDir tempDir: File) {
        val tempFile = File(tempDir, "Test.groovy")
        tempFile.writeText("class Test { void foo() { println 'bar' } }")

        val output = captureOutput {
            runWithContext("check", tempFile.absolutePath)
        }

        // Valid file should produce "OK" output
        assertTrue(output.contains("OK") || output.contains(tempFile.name))
    }

    @Test
    fun `test check command reports errors`(@TempDir tempDir: File) {
        val errorFile = File(tempDir, "Error.groovy")
        errorFile.writeText("class Error { void foo() { println 'bar' ") // Missing closing braces

        val output = captureOutput {
            runWithContext("check", errorFile.absolutePath)
        }

        // The check command should output the error file path or an ERROR severity
        assertTrue(
            output.contains(errorFile.name) || output.contains("ERROR"),
            "Expected error file name or ERROR in output, got: $output",
        )
    }

    @Test
    fun `test execute command runs groovy version`() {
        val output = captureOutput {
            runWithContext("execute", "groovy.version")
        }

        // Should output something containing version info
        assertTrue(output.isNotEmpty())
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
