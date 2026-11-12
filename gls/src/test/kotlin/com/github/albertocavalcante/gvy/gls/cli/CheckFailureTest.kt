package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.ExecutionException
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
    fun `sarif accounts for each clean and incomplete file`(@TempDir dir: File) {
        val clean = File(dir, "clean.groovy").apply { writeText("") }
        val failed = File(dir, "failed.groovy").apply { writeText("") }
        val output = File(dir, "results.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { file, _ ->
            if (file == failed) throw IOException("unavailable")
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        }))
        assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--format", "sarif", "--output", output.path, clean.path, failed.path))
        }
        val run = Json.parseToJsonElement(output.readText()).jsonObject.getValue("runs").jsonArray.single().jsonObject
        val checks = run.getValue("properties").jsonObject.getValue("gvyFileChecks").jsonArray.map { entry ->
            val record = entry.jsonObject
            Triple(
                record.getValue("uri").jsonPrimitive.content,
                record.getValue("status").jsonPrimitive.content,
                record.getValue("failureIds").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        assertEquals(
            listOf(
                Triple(clean.invariantSeparatorsPath, "complete", emptyList()),
                Triple(failed.invariantSeparatorsPath, "incomplete", listOf("analysis")),
            ),
            checks,
        )
        assertTrue(run.getValue("results").jsonArray.isEmpty())
    }

    @Test
    fun `sarif records a clean file when there are no findings`(@TempDir dir: File) {
        val file = File(dir, "clean.groovy").apply { writeText("") }
        val output = File(dir, "clean.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ ->
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        }))
        command.parse(listOf("check", "--format", "sarif", "--output", output.path, file.path))
        val run = Json.parseToJsonElement(output.readText()).jsonObject.getValue("runs").jsonArray.single().jsonObject
        val check = run.getValue("properties").jsonObject.getValue("gvyFileChecks").jsonArray.single().jsonObject
        assertEquals(file.invariantSeparatorsPath, check.getValue("uri").jsonPrimitive.content)
        assertEquals("complete", check.getValue("status").jsonPrimitive.content)
        assertTrue(check.getValue("failureIds").jsonArray.isEmpty())
        assertTrue(run.getValue("results").jsonArray.isEmpty())
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
        assertTrue(json.contains("provider:broken") && json.contains("offline") && json.contains(file.name), json)
    }

    @Test
    fun `later errors make the command fail after many warnings`(@TempDir dir: File) {
        val file = File(dir, "many.groovy").apply { writeText("") }
        val range = org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 1))
        val warning = org.eclipse.lsp4j.Diagnostic(range, "warning").apply {
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Warning
        }
        val error = org.eclipse.lsp4j.Diagnostic(range, "late error").apply {
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
        }
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ ->
            DiagnosticsCollectionResult(List(101) { warning } + error, emptyList(), emptyList())
        }))
        val result = assertFailsWith<ProgramResult> { command.parse(listOf("check", file.path)) }
        assertEquals(1, result.statusCode)
    }

    @Test
    fun `workspace dependency failure makes text check incomplete`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("class Good {}") }
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            dependencyStatus = { null },
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
            dependencyStatus = { null },
        ))
        assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--workspace", dir.path, "--format", "sarif", "--output", output.path, file.path))
        }
        val json = output.readText()
        assertTrue(json.contains("executionSuccessful\": false"), json)
        assertTrue(json.contains("Workspace dependency resolution failed"), json)
    }

    @Test
    fun `workspace dependency warning is incomplete with its reason`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("") }
        val output = File(dir, "warning.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            dependencyStatus = { ResolutionStatus.Warning("ZERO_DEPENDENCIES", "declared dependencies unavailable") },
            startWorkspace = { _, _ -> },
        ))
        assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--workspace", dir.path, "--format", "sarif", "--output", output.path, file.path))
        }
        val json = output.readText()
        assertTrue(json.contains("executionSuccessful\": false"), json)
        assertTrue(json.contains("ZERO_DEPENDENCIES") && json.contains("declared dependencies unavailable"), json)
        assertTrue(json.contains("workspace"), json)
    }

    @Test
    fun `workspace initialization failure is reported in sarif`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("") }
        val output = File(dir, "startup.sarif")
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            startWorkspace = { _, _ -> throw ExecutionException(IOException("startup unavailable")) },
        ))
        val result = assertFailsWith<ProgramResult> {
            command.parse(listOf("check", "--workspace", dir.path, "--format", "sarif", "--output", output.path, file.path))
        }
        assertEquals(1, result.statusCode)
        val json = output.readText()
        assertTrue(json.contains("executionSuccessful\": false"), json)
        assertTrue(json.contains("startup unavailable") && json.contains(file.name), json)
    }

    @Test
    fun `workspace initialization propagates wrapped cancellation and fatal errors`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("") }
        val failures = listOf(CancellationException("cancelled"), LinkageError("fatal"))
        failures.forEach { failure ->
            val command = GlsCommand(FormatCommand(), CheckCommand(
                analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
                startWorkspace = { _, _ -> throw ExecutionException(failure) },
            ))
            val observed = assertFailsWith<Throwable> {
                command.parse(listOf("check", "--workspace", dir.path, file.path))
            }
            assertTrue(observed === failure)
        }
    }

    @Test
    fun `file only checks do not wait for workspace dependencies`(@TempDir dir: File) {
        val file = File(dir, "good.groovy").apply { writeText("class Good {}") }
        val command = GlsCommand(FormatCommand(), CheckCommand(
            analyze = { _, _ -> DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList()) },
            dependencyStatus = { error("no workspace should be resolved") },
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
    fun `shutdown failure does not replace cancellation or fatal error`(@TempDir dir: File) {
        val file = File(dir, "cancel.groovy").apply { writeText("") }
        listOf(CancellationException("cancelled"), LinkageError("fatal")).forEach { failure ->
            val cleanup = IOException("shutdown failed")
            val command = GlsCommand(FormatCommand(), CheckCommand(
                analyze = { _, _ -> throw failure },
                stopServer = { server ->
                    server.shutdown().get()
                    throw cleanup
                },
            ))
            val observed = assertFailsWith<Throwable> { command.parse(listOf("check", file.path)) }
            assertTrue(observed === failure)
            assertEquals(listOf(cleanup), observed.suppressed.toList())
        }
    }

    @Test
    fun `fatal error propagates`(@TempDir dir: File) {
        val file = File(dir, "fatal.groovy").apply { writeText("") }
        val command = GlsCommand(FormatCommand(), CheckCommand(analyze = { _, _ -> throw LinkageError("fatal") }))
        assertFailsWith<LinkageError> { command.parse(listOf("check", file.path)) }
    }
}
