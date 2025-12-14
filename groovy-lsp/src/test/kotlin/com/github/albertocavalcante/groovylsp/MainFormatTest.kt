package com.github.albertocavalcante.groovylsp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class MainFormatTest {

    @Test
    fun `format prints formatted content for a file`() {
        val outContent = ByteArrayOutputStream()
        val printStream = PrintStream(outContent)

        val tempFile = File.createTempFile("Format", ".groovy")
        tempFile.writeText("class A{ def x(){  println 'hi' } }")
        tempFile.deleteOnExit()

        runFormat(listOf(tempFile.absolutePath), printStream)

        val output = outContent.toString()
        assertTrue(output.contains("class A"), "Expected formatted output to include class declaration, got: $output")
    }

    @Test
    fun `format skips missing files without crashing`() {
        val outContent = ByteArrayOutputStream()
        val printStream = PrintStream(outContent)

        runFormat(listOf("does-not-exist.groovy"), printStream)

        // We print missing file errors to stderr; just ensure we didn't crash and produced no stdout output.
        assertTrue(outContent.toString().isBlank(), "Expected no stdout output for missing file.")
    }
}
