package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
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
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { file, _ ->
            if (file == bad) throw IOException("analysis unavailable")
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        }))
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
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { requested, _ ->
            requested.delete()
            requested.readText()
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        }))
        val result = assertFailsWith<ProgramResult> { command.parse(listOf("check", file.path)) }
        assertEquals(1, result.statusCode)
    }

    @Test
    fun `sarif marks failed analysis unsuccessful`(@TempDir dir: File) {
        val file = File(dir, "bad.groovy").apply { writeText("") }
        val outputFile = File(dir, "result.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ -> throw IOException("analysis unavailable") }))
        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--format", "sarif", "--output", outputFile.path, file.path))
        }
        assertEquals(1, result.statusCode)
        assertTrue(outputFile.readText().contains("\"executionSuccessful\": false"))
    }

    @Test
    fun `provider failure retains partial diagnostics and fails sarif`(@TempDir dir: File) {
        val file = File(dir, "partial.groovy").apply { writeText("") }
        val outputFile = File(dir, "partial.sarif")
        val diagnostic = org.eclipse.lsp4j.Diagnostic(
            org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 1)),
            "partial warning",
        )
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ ->
            DiagnosticsCollectionResult(
                listOf(diagnostic), listOf("broken"), listOf(DiagnosticProviderFailure("broken", "IOException", "offline")),
            )
        }))
        assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--format", "sarif", "--output", outputFile.path, file.path))
        }
        val json = outputFile.readText()
        assertTrue(json.contains("partial warning"), json)
        assertTrue(json.contains("executionSuccessful\": false"), json)
        assertTrue(json.contains("broken") && json.contains("offline") && json.contains(file.name), json)
    }

    @Test
    fun `workspace dependency failure makes text check incomplete`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("class Good {}") }
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            dependenciesReady = { false },
        ))
        val output = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(output))
        try {
            val failure = assertFailsWith<ProgramResult> {
                command.parse(listOf("--no-color", "check", "--workspace", dir.path, file.path))
            }
            assertEquals(1, failure.statusCode)
        } finally {
            System.setOut(previous)
        }
        assertTrue(output.toString().contains("Workspace dependency resolution failed"))
        assertFalse(output.toString().contains("OK: ${file.path}"))
    }

    @Test
    fun `workspace dependency failure makes sarif execution unsuccessful`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("class Good {}") }
        val output = File(dir, "workspace.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            dependenciesReady = { false },
        ))
        assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--workspace", dir.path, "--format", "sarif", "--output", output.path, file.path))
        }
        val json = output.readText()
        assertTrue(json.contains("executionSuccessful\": false"), json)
        assertTrue(json.contains("Workspace dependency resolution failed"), json)
    }

    @Test
    fun `file only checks do not wait for workspace dependencies`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("class Good {}") }
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            dependenciesReady = { error("no workspace should be resolved") },
        ))
        command.parse(listOf("check", file.path))
    }

    @Test
    fun `cancellation propagates`(@TempDir dir: File) {
        val file = File(dir, "cancel.groovy").apply { writeText("") }
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ -> throw CancellationException("cancelled") }))
        assertFailsWith<CancellationException> { command.parse(listOf("check", file.path)) }
    }

    @Test
    fun `fatal error propagates`(@TempDir dir: File) {
        val file = File(dir, "fatal.groovy").apply { writeText("") }
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ -> throw LinkageError("fatal") }))
        assertFailsWith<LinkageError> { command.parse(listOf("check", file.path)) }
    }
}
