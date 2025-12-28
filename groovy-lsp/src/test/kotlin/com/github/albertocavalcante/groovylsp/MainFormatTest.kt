package com.github.albertocavalcante.groovylsp

import com.github.ajalt.clikt.core.main
import com.github.albertocavalcante.groovylsp.cli.FormatCommand
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class MainFormatTest {

    @Test
    fun `format prints formatted content for a file`() {
        val tempFile = File.createTempFile("Format", ".groovy")
        tempFile.writeText("class A{ def x(){  println 'hi' } }")
        tempFile.deleteOnExit()

        val output = captureOutput {
            FormatCommand().main(listOf(tempFile.absolutePath))
        }

        assertTrue(output.contains("class A"), "Expected formatted output to include class declaration, got: $output")
    }

    @Test
    fun `format throws for missing files`() {
        // Clikt will throw an exception for non-existent files when mustExist = true
        assertThrows<com.github.ajalt.clikt.core.BadParameterValue> {
            FormatCommand().main(listOf("does-not-exist.groovy"))
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
