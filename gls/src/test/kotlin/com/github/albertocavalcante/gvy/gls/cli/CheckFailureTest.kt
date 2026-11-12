package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckFailureTest {
    @Test
    fun `analysis failure fails mixed checks and identifies the failed file`(@TempDir dir: File) {
        val good = File(dir, "good.groovy").apply { writeText("class Good {}") }
        val bad = File(dir, "bad.groovy").apply { writeText("class Bad {}") }
        val command = GlsCommand(FormatCommand(), CheckCommand { file, _ ->
            if (file == bad) throw IOException("analysis unavailable")
            emptyList()
        })
        val output = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(output))
        try {
            val result = assertFailsWith<ProgramResult> {
                command.parse(listOf("--no-color", "check", good.path, bad.path))
            }
            assertEquals(1, result.statusCode)
        } finally {
            System.setOut(previous)
        }
        val text = output.toString()
        assertTrue(text.contains("OK: ${good.path}"), text)
        assertTrue(text.contains(bad.path) && text.contains("analysis unavailable"), text)
        assertFalse(text.contains("OK: ${bad.path}"), text)
    }

    @Test
    fun `file read failure fails the command`(@TempDir dir: File) {
        val file = File(dir, "gone.groovy").apply { writeText("class Gone {}") }
        val command = GlsCommand(FormatCommand(), CheckCommand { requested, _ ->
            requested.delete()
            requested.readText()
            emptyList()
        })
        val result = assertFailsWith<ProgramResult> { command.parse(listOf("check", file.path)) }
        assertEquals(1, result.statusCode)
    }

    @Test
    fun `sarif marks failed analysis unsuccessful`(@TempDir dir: File) {
        val file = File(dir, "bad.groovy").apply { writeText("") }
        val outputFile = File(dir, "result.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand { _, _ -> throw IOException("analysis unavailable") })
        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--format", "sarif", "--output", outputFile.path, file.path))
        }
        assertEquals(1, result.statusCode)
        assertTrue(outputFile.readText().contains("\"executionSuccessful\": false"))
    }

    @Test
    fun `cancellation propagates`(@TempDir dir: File) {
        val file = File(dir, "cancel.groovy").apply { writeText("") }
        val command = GlsCommand(FormatCommand(), CheckCommand { _, _ -> throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { command.parse(listOf("check", file.path)) }
    }

    @Test
    fun `fatal error propagates`(@TempDir dir: File) {
        val file = File(dir, "fatal.groovy").apply { writeText("") }
        val command = GlsCommand(FormatCommand(), CheckCommand { _, _ -> throw LinkageError("fatal") })
        assertFailsWith<LinkageError> { command.parse(listOf("check", file.path)) }
    }
}
