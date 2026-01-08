package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.albertocavalcante.groovylsp.cli.GlsCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Result of a CLI command execution, capturing both output and exit code.
 */
private data class CommandResult(val output: String, val exitCode: Int)

class MainTest {

    /**
     * Helper to run a subcommand through the root GlsCommand,
     * ensuring proper context setup (e.g., Terminal object).
     */
    private fun runWithContext(vararg args: String) {
        GlsCommand().parse(args.toList())
    }

    @Test
    fun `test version command outputs version string`() {
        val result = captureOutput {
            runWithContext("version")
        }
        assertEquals(0, result.exitCode, "version command should succeed")
        assertTrue(result.output.contains("gls") && result.output.contains("version"))
    }

    @Test
    fun `test help is available on root command`() {
        val command = GlsCommand()

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

        val result = captureOutput {
            runWithContext("format", tempFile.absolutePath)
        }

        assertEquals(0, result.exitCode, "format command should succeed")
        // The formatter should produce some output (the formatted file content)
        assertTrue(result.output.isNotEmpty(), "Expected formatted output, got empty string")
    }

    @Test
    fun `test check command checks groovy file`(@TempDir tempDir: File) {
        val tempFile = File(tempDir, "Test.groovy")
        tempFile.writeText("class Test { void foo() { println 'bar' } }")

        val result = captureOutput {
            runWithContext("check", tempFile.absolutePath)
        }

        assertEquals(0, result.exitCode, "check command should succeed for valid file")
        // Valid file should produce "OK" output
        assertTrue(result.output.contains("OK") || result.output.contains(tempFile.name))
    }

    @Test
    fun `test check command reports errors`(@TempDir tempDir: File) {
        val errorFile = File(tempDir, "Error.groovy")
        errorFile.writeText("class Error { void foo() { println 'bar' ") // Missing closing braces

        val result = captureOutput {
            runWithContext("check", errorFile.absolutePath)
        }

        assertEquals(1, result.exitCode, "check command should exit with code 1 for errors")
        // The check command should output the error file path or an ERROR severity
        assertTrue(
            result.output.contains(errorFile.name) || result.output.contains("ERROR"),
            "Expected error file name or ERROR in output, got: ${result.output}",
        )
    }

    @Test
    fun `test check respects no-color flag`(@TempDir tempDir: File) {
        val errorFile = File(tempDir, "Error.groovy")
        errorFile.writeText("class Error { void foo() { println 'bar' ") // Missing closing braces

        val result = captureOutput {
            // --no-color is a global option, must precise before subcommand
            runWithContext("--no-color", "check", errorFile.absolutePath)
        }

        assertEquals(1, result.exitCode, "check command should exit with code 1 for errors")
        // Output should contain "[ERROR]" (plain text)
        assertTrue(result.output.contains("[ERROR]"), "Expected plain [ERROR] tag, got: ${result.output}")

        // Output should NOT contain ANSI escape codes
        val ansiEscape = "\u001B"
        assertTrue(!result.output.contains(ansiEscape), "Output should not contain ANSI codes, got: ${result.output}")
    }

    @Test
    fun `test execute command runs groovy version`() {
        val result = captureOutput {
            runWithContext("execute", "groovy.version")
        }

        assertEquals(0, result.exitCode, "execute command should succeed")
        // Should output something containing version info
        assertTrue(result.output.isNotEmpty())
    }

    /**
     * Captures stdout output during the execution of a block.
     * Returns both output and exit code so tests can assert on command success/failure.
     */
    private fun captureOutput(block: () -> Unit): CommandResult {
        val originalOut = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        var exitCode = 0
        try {
            block()
        } catch (e: ProgramResult) {
            exitCode = e.statusCode
        } finally {
            System.setOut(originalOut)
        }
        return CommandResult(baos.toString(), exitCode)
    }
}
