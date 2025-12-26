package com.github.albertocavalcante.groovylsp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MainTest {

    @Test
    fun `test version command`() {
        val outContent = ByteArrayOutputStream()
        val printStream = PrintStream(outContent)

        runVersion(printStream)
        assertTrue(outContent.toString().contains("gls version"))
    }

    @Test
    fun `test help command`() {
        val outContent = ByteArrayOutputStream()
        val printStream = PrintStream(outContent)

        printHelp(printStream)
        assertTrue(outContent.toString().contains("gls - Groovy Language Server"))
    }

    @Test
    fun `test no args defaults to stdio`() {
        // This will try to start the server and block, so we can't easily test it without mocking.
        // We need to refactor Main.kt to allow dependency injection or mocking of the runner.
    }

    @Test
    fun `test check command`() {
        val outContent = ByteArrayOutputStream()
        val printStream = PrintStream(outContent)

        // Create a temporary file to check
        val tempFile = java.io.File.createTempFile("Test", ".groovy")
        tempFile.writeText("class Test { void foo() { println 'bar' } }")
        tempFile.deleteOnExit()

        runCheck(listOf(tempFile.absolutePath), printStream)
        // Valid file might not produce output, so just ensure no exceptions

        // Now test with an error file
        val errorFile = java.io.File.createTempFile("Error", ".groovy")
        errorFile.writeText("class Error { void foo() { println 'bar' ") // Missing closing braces
        errorFile.deleteOnExit()

        outContent.reset()
        runCheck(listOf(errorFile.absolutePath), printStream)
        val output = outContent.toString()
        // The check command should output the error file path and an ERROR severity diagnostic
        assertTrue(
            output.contains(errorFile.name) || output.contains("ERROR"),
            "Expected error file name or ERROR in output, got: $output",
        )
    }

    @Test
    fun `test execute command`() {
        val outContent = ByteArrayOutputStream()
        val printStream = PrintStream(outContent)

        runExecute(listOf("groovy.version"), printStream)
        assertTrue(outContent.toString().contains(Version.current))
    }
}
