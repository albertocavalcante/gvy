// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.contentHash
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentAnalysisNotification
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.google.gson.Gson
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentAnalysisNotificationTest {
    @Test
    fun `source model limitations share the terminal Problems and status snapshot`() = runTest {
        val uri = URI.create("file:///workspace/A.groovy")
        val content = "class A {}"
        val documents = DocumentProvider().apply { put(uri, content, 3, "session") }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = CompilationResult(
            true,
            null,
            emptyList(),
            content,
            parseCompleted = true,
            outcome = ParseOutcome(),
            sourceMembershipKnown = false,
            sourceVisibilityKnown = false,
        )
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        val notifications = mutableListOf<DocumentAnalysisNotification>()
        val client = mockk<GroovyLanguageClient>(relaxed = true)
        every { client.documentAnalysis(capture(notifications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator = DiagnosticsOrchestrator(
            scope,
            compilation,
            providers,
            documents,
            ServerConfiguration(),
            { client },
            documentAnalysisSupported = { true },
        )
        try {
            orchestrator.trigger(uri, content)
            val terminal = orchestrator.awaitCurrentDiagnostics(uri, 3)
            assertEquals(1, terminal.diagnostics.count { it.code?.left == "gvy.sourceModel" })
            val status = notifications.single()
            assertEquals(terminal.version, status.version)
            assertEquals(terminal.runId, status.runId)
            val compiler = Gson().toJsonTree(status.compiler).asJsonObject
            assertEquals(false, compiler.get("sourceMembershipKnown")?.asBoolean)
            assertEquals(false, compiler.get("sourceVisibilityKnown")?.asBoolean)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `config errors and provider failure produce one consistent notice while preserving both literals`(
        @TempDir root: Path,
    ) = runTest {
        val uri = root.resolve("Source.groovy").toUri()
        val content = "class Source {}"
        val documents = DocumentProvider().apply { put(uri, content, 3, "session") }
        val configs = listOf(root.resolve(".codenarc"), root.resolve("codenarc.groovy"))
        val literal = Range(Position(0, 18), Position(0, 38))
        val configDiagnostics = configs.map { configDiagnostic(it, literal) }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result =
            CompilationResult(true, null, emptyList(), content, parseCompleted = true, outcome = ParseOutcome())
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        val failure = DiagnosticProviderFailure("codenarc", "Failure", "Analyzer failed\n" + "x".repeat(1000))
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(configDiagnostics, listOf("codenarc"), listOf(failure))
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val notifications = mutableListOf<DocumentAnalysisNotification>()
        val client = mockk<GroovyLanguageClient>(relaxed = true)
        every { client.publishDiagnostics(capture(publications)) } just Runs
        every { client.documentAnalysis(capture(notifications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator = DiagnosticsOrchestrator(
            scope,
            compilation,
            providers,
            documents,
            ServerConfiguration(),
            { client },
            documentAnalysisSupported = { true },
        )
        try {
            orchestrator.trigger(uri, content)
            val terminal = orchestrator.awaitCurrentDiagnostics(uri, 3)
            val notice = terminal.diagnostics.single { it.code?.left == "gvy.providerFailure" }
            assertTrue(notice.message.contains("Analyzer failed"))
            assertTrue(notice.message.length <= 450 && !notice.message.contains('\n'))
            assertEquals(
                configs.map { it.toUri().toString() }.toSet(),
                notice.relatedInformation.map { it.location.uri }.toSet(),
            )
            assertEquals(configs.first().toUri().toString(), (notice.data as Map<*, *>)["configurationUri"])
            assertEquals(listOf(failure), terminal.failures)
            assertPublishedNotices(notifications, publications, uri, notice)
            configs.forEach { path ->
                val configNotice = publications.last { it.uri == path.toUri().toString() }.diagnostics.single()
                assertEquals(literal, configNotice.range)
            }
            assertTrue(terminal.diagnostics.none { it.code?.left == "gvy.sourceModel" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reopened document reports its own session and monotonic run identity`() = runTest {
        val uri = URI.create("file:///workspace/A.groovy")
        val content = "class A {}"
        val documents = DocumentProvider().apply { put(uri, content, 1, "old-session") }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        every { result.outcome } returns ParseOutcome()
        every { result.sourceMembershipKnown } returns null
        every { result.sourceVisibilityKnown } returns null
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        val notifications = mutableListOf<DocumentAnalysisNotification>()
        val client = mockk<GroovyLanguageClient>(relaxed = true)
        every { client.documentAnalysis(capture(notifications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator = DiagnosticsOrchestrator(
            scope,
            compilation,
            providers,
            documents,
            ServerConfiguration(),
            { client },
            documentAnalysisSupported = { true },
        )

        orchestrator.trigger(uri, content)
        orchestrator.awaitDiagnostics(uri)
        orchestrator.close(uri)
        documents.put(uri, content, 1, "new-session")
        orchestrator.trigger(uri, content)
        orchestrator.awaitDiagnostics(uri)

        assertEquals(listOf("old-session", "new-session"), notifications.map { it.sessionId })
        assertTrue(notifications[1].runId > notifications[0].runId)
        assertTrue(notifications.all { it.version == 1 && it.compiler.complete })
        scope.cancel()
    }

    @Test
    fun `client capability gates notification`() = runTest {
        val uri = URI.create("file:///workspace/A.groovy")
        val documents = DocumentProvider().apply { put(uri, "class A {}", 1, "session") }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        every { result.outcome } returns ParseOutcome()
        every { result.sourceMembershipKnown } returns null
        every { result.sourceVisibilityKnown } returns null
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(emptyList(), emptyList(), emptyList())
        val notifications = mutableListOf<DocumentAnalysisNotification>()
        val client = mockk<GroovyLanguageClient>(relaxed = true)
        every { client.documentAnalysis(capture(notifications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator =
            DiagnosticsOrchestrator(scope, compilation, providers, documents, ServerConfiguration(), { client })
        orchestrator.trigger(uri, "class A {}")
        orchestrator.awaitDiagnostics(uri)
        assertTrue(notifications.isEmpty())
        scope.cancel()
    }

    private fun configDiagnostic(path: Path, literal: Range): Diagnostic {
        val text = "ruleset { ruleset('rulesets/empty.xml') }"
        Files.writeString(path, text)
        return Diagnostic(
            Range(Position(0, 0), Position(0, 0)),
            "Invalid ruleset ${path.fileName}",
            DiagnosticSeverity.Information,
            "gvy",
            "gvy.providerFailure",
        ).apply {
            data = mapOf(
                "provider" to "codenarc",
                "configurationUri" to path.toUri().toString(),
                "configurationHash" to contentHash(text),
            )
            relatedInformation =
                listOf(DiagnosticRelatedInformation(Location(path.toUri().toString(), literal), "bad rule"))
        }
    }

    private fun assertPublishedNotices(
        notifications: List<DocumentAnalysisNotification>,
        publications: List<PublishDiagnosticsParams>,
        uri: URI,
        notice: Diagnostic,
    ) {
        val status = notifications.single()
        assertEquals(1, status.providerFailures.size)
        assertEquals(notice.message, status.providerFailures.single().message)
        assertEquals(null, status.compiler.sourceModelMessage)
        assertTrue(
            publications.last {
                it.uri == uri.toString()
            }.diagnostics.none { it.code?.left == "gvy.sourceModel" },
        )
    }
}
