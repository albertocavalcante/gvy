package com.github.albertocavalcante.gvy.gls

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.albertocavalcante.gvy.fmt.FormatOutcome
import com.github.albertocavalcante.gvy.gls.cli.FormatCommand
import com.github.albertocavalcante.gvy.gls.cli.GlsCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.concurrent.CancellationException

@Isolated("Captures the process output streams")
class MainFormatTest {

    /**
     * Helper to run a subcommand through the root GlsCommand,
     * ensuring proper context setup (e.g., Terminal object).
     */
    private fun runWithContext(vararg args: String, formatCommand: FormatCommand = FormatCommand()) {
        GlsCommand(formatCommand).parse(args.toList())
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
    fun `format writes UTF-8 source through a legacy stdout encoding`(@TempDir directory: File) {
        val input = "println 'hello 世界 😀'\n"
        val file = File(directory, "Input.groovy").apply { writeText(input) }

        val output = captureOutput(Charset.forName("windows-1252")) {
            runWithContext(
                "format",
                file.absolutePath,
                formatCommand = FormatCommand { source -> FormatOutcome.Formatted(source) },
            )
        }

        assertThat(output).isEqualTo(input)
        assertThat(file.readText()).isEqualTo(input)
    }

    @Test
    fun `format preserves document newlines when writing multiple UTF-8 files`(@TempDir directory: File) {
        val inputs = listOf("println '世界 😀'", "println 'déjà λ'\n", "println '世界'\r\n")
        val files = inputs.mapIndexed { index, input ->
            File(directory, "Input$index.groovy").apply { writeText(input) }
        }

        val output = captureOutput(Charset.forName("windows-1252")) {
            runWithContext(
                "format",
                *files.map { it.absolutePath }.toTypedArray(),
                formatCommand = FormatCommand { source -> FormatOutcome.Unchanged(source) },
            )
        }

        assertThat(output).isEqualTo(inputs[0] + System.lineSeparator() + inputs[1] + inputs[2])
        assertThat(files.map { it.readText() }).containsExactlyElementsOf(inputs)
    }

    @Test
    fun `format warns on stderr and prints the input when the result would change the program`() {
        val tempFile = File.createTempFile("Refused", ".groovy")
        val input = "def y = x as BigDecimal\n// 世界 😀\n"
        tempFile.writeText(input)
        tempFile.deleteOnExit()

        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val output = try {
            captureOutput(Charset.forName("windows-1252")) {
                runWithContext(
                    "format",
                    tempFile.absolutePath,
                    formatCommand = FormatCommand { original -> FormatOutcome.Refused(original, "known unsafe edit") },
                )
            }
        } finally {
            System.setErr(originalErr)
        }

        assertThat(output).isEqualTo(input)
        val warning = err.toString(Charsets.UTF_8)
        assertTrue(
            warning.contains("left unformatted") && warning.contains(tempFile.path),
            "Expected a refusal warning on stderr, got: $warning",
        )
    }

    @Test
    fun `format prints a formatted document containing as casts`() {
        val tempFile = File.createTempFile("Cast", ".groovy")
        tempFile.writeText("class A {\ndef f(Object x) {\ndef y = x as BigDecimal\n}\n}")
        tempFile.deleteOnExit()

        val output = captureOutput { runWithContext("format", tempFile.absolutePath) }

        assertTrue(
            output.contains("class A {\n    def f(Object x) {\n        def y = x as BigDecimal\n    }\n}"),
            "Expected a formatted document with intact cast tokens, got: $output",
        )
    }

    @Test
    fun `format throws for missing files`() {
        // Clikt will throw an exception for non-existent files when mustExist = true
        // Use CliktError as the base type since the specific exception type may vary
        assertThrows<CliktError> {
            runWithContext("format", "does-not-exist.groovy")
        }
    }

    @Test
    fun `engine failure propagates without printing a successful document`(@TempDir directory: File) {
        val input = "def x = 1\n"
        val file = File(directory, "Input.groovy").apply { writeText(input) }
        listOf(IOException("engine failed"), CancellationException("engine cancelled"), LinkageError("engine fatal"))
            .forEach { failure ->
                val output = captureOutput {
                    assertThatThrownBy {
                        runWithContext("format", file.absolutePath, formatCommand = FormatCommand { throw failure })
                    }.isSameAs(failure)
                }

                assertThat(output).isEmpty()
                assertThat(file.readText()).isEqualTo(input)
            }
    }

    @Test
    fun `format fails when stdout cannot receive the formatted document`(@TempDir directory: File) {
        val file = File(directory, "Input.groovy").apply { writeText("println 'hello 世界 😀'\n") }
        val originalOut = System.out
        val originalErr = System.err
        val failedOut = PrintStream(object : OutputStream() {
            override fun write(value: Int) = throw IOException("sink failed")
        })
        System.setOut(failedOut)
        try {
            assertThatThrownBy {
                runWithContext(
                    "format",
                    file.absolutePath,
                    formatCommand = FormatCommand { source -> FormatOutcome.Unchanged(source) },
                )
            }.isInstanceOf(CliktError::class.java)
                .hasMessage("Failed to write output to stdout")
            assertThat(System.out).isSameAs(failedOut)
            assertThat(System.err).isSameAs(originalErr)
        } finally {
            System.setOut(originalOut)
            failedOut.close()
        }
    }

    /**
     * Captures stdout output during the execution of a block.
     */
    private fun captureOutput(outputCharset: Charset = Charsets.UTF_8, block: () -> Unit): String {
        val originalOut = System.out
        val originalErr = System.err
        val baos = ByteArrayOutputStream()
        val capturedOut = PrintStream(baos, true, outputCharset)
        System.setOut(capturedOut)
        try {
            block()
            assertThat(System.out).isSameAs(capturedOut)
            assertThat(System.err).isSameAs(originalErr)
        } finally {
            System.setOut(originalOut)
            capturedOut.close()
        }
        return baos.toString(Charsets.UTF_8)
    }
}
