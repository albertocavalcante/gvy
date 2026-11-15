// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.contentHash
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationDiagnosticsLifecycleTest {
    @Test
    fun `shared configuration notices merge and clear on correction`(@TempDir root: Path) = runTest {
        val config = root.resolve(".codenarc")
        val oldText = "ruleset { ruleset('rulesets/empty.xml') }"
        Files.writeString(config, oldText)
        val first = root.resolve("First.groovy").toUri()
        val second = root.resolve("Second.groovy").toUri()
        val documents = DocumentProvider().apply {
            put(first, "class First {}", 1)
            put(second, "class Second {}", 1)
        }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        every { result.outcome } returns null
        every { result.sourceMembershipKnown } returns null
        every { result.sourceVisibilityKnown } returns null
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        var notices = listOf(configNotice(config.toUri(), contentHash(oldText)))
        coEvery { providers.collectDiagnostics(any(), any(), any()) } coAnswers {
            DiagnosticsCollectionResult(notices, listOf("codenarc"), emptyList())
        }
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>()
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator =
            DiagnosticsOrchestrator(scope, compilation, providers, documents, ServerConfiguration(), { client })

        orchestrator.trigger(first, "class First {}")
        orchestrator.awaitDiagnostics(first)
        orchestrator.trigger(second, "class Second {}")
        orchestrator.awaitDiagnostics(second)
        assertEquals(1, publications.last { it.uri == config.toUri().toString() }.diagnostics.size)
        orchestrator.close(first)
        assertEquals(1, publications.last { it.uri == config.toUri().toString() }.diagnostics.size)

        Files.writeString(config, "ruleset { ruleset('rulesets/basic.xml') }")
        notices = emptyList()
        orchestrator.invalidateWorkspaceConfiguration()
        assertTrue(publications.last { it.uri == config.toUri().toString() }.diagnostics.isEmpty())
        orchestrator.trigger(second, "class Second {}")
        orchestrator.awaitDiagnostics(second)
        assertTrue(publications.last { it.uri == config.toUri().toString() }.diagnostics.isEmpty())
        scope.cancel()
    }

    @Test
    fun `unsaved configuration edits never receive stale disk ranges`(@TempDir root: Path) = runTest {
        val config = root.resolve(".codenarc")
        val text = "ruleset { ruleset('rulesets/empty.xml') }"
        Files.writeString(config, text)
        val source = root.resolve("Source.groovy").toUri()
        val documents = DocumentProvider().apply {
            put(source, "class Source {}", 1)
            put(config.toUri(), text, 1)
        }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        every { result.outcome } returns null
        every { result.sourceMembershipKnown } returns null
        every { result.sourceVisibilityKnown } returns null
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(
                listOf(configNotice(config.toUri(), contentHash(text))),
                listOf("codenarc"),
                emptyList(),
            )
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>()
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator =
            DiagnosticsOrchestrator(scope, compilation, providers, documents, ServerConfiguration(), { client })
        orchestrator.trigger(source, "class Source {}")
        orchestrator.awaitDiagnostics(source)
        documents.update(config.toUri(), "// unsaved edit\n$text", 2)
        orchestrator.documentChanged(config.toUri())
        assertTrue(publications.last { it.uri == config.toUri().toString() }.diagnostics.isEmpty())
        orchestrator.trigger(source, "class Source {}")
        orchestrator.awaitDiagnostics(source)
        assertTrue(publications.last { it.uri == config.toUri().toString() }.diagnostics.isEmpty())
        scope.cancel()
    }

    @Test
    fun `changed closed configuration cannot publish an obsolete literal range`(@TempDir root: Path) = runTest {
        val config = root.resolve(".codenarc")
        val text = "ruleset { ruleset('rulesets/empty.xml') }"
        Files.writeString(config, text)
        val source = root.resolve("Source.groovy").toUri()
        val documents = DocumentProvider().apply { put(source, "class Source {}", 1) }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        every { result.outcome } returns null
        every { result.sourceMembershipKnown } returns null
        every { result.sourceVisibilityKnown } returns null
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        coEvery { providers.collectDiagnostics(any(), any(), any()) } returns
            DiagnosticsCollectionResult(
                listOf(configNotice(config.toUri(), contentHash(text))),
                listOf("codenarc"),
                emptyList(),
            )
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>()
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        var lookups = 0
        val orchestrator = DiagnosticsOrchestrator(
            scope,
            compilation,
            providers,
            documents,
            ServerConfiguration(),
            {
                if (++lookups == 2) Files.writeString(config, "// changed after initial check\n$text")
                client
            },
        )
        orchestrator.trigger(source, "class Source {}")
        orchestrator.awaitDiagnostics(source)
        assertTrue(publications.none { it.uri == config.toUri().toString() && it.diagnostics.isNotEmpty() })
        scope.cancel()
    }

    @Test
    fun `delayed obsolete run cannot republish configuration after invalidation`(@TempDir root: Path) = runTest {
        val config = root.resolve(".codenarc")
        val text = "ruleset { ruleset('rulesets/empty.xml') }"
        Files.writeString(config, text)
        val source = root.resolve("Source.groovy").toUri()
        val documents = DocumentProvider().apply { put(source, "class Source {}", 1) }
        val compilation = mockk<GroovyCompilationService>()
        val providers = mockk<DiagnosticsService>()
        val result = mockk<CompilationResult>()
        every { result.diagnostics } returns emptyList()
        every { result.parseCompleted } returns true
        every { result.outcome } returns null
        every { result.sourceMembershipKnown } returns null
        every { result.sourceVisibilityKnown } returns null
        coEvery { compilation.compileAsync(any(), any(), any()) } returns CompletableDeferred(result)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { providers.collectDiagnostics(any(), any(), any()) } coAnswers {
            started.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            DiagnosticsCollectionResult(
                listOf(configNotice(config.toUri(), contentHash(text))),
                listOf("codenarc"),
                emptyList(),
            )
        }
        val publications = mutableListOf<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>()
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val scope = CoroutineScope(SupervisorJob())
        val orchestrator =
            DiagnosticsOrchestrator(scope, compilation, providers, documents, ServerConfiguration(), { client })
        orchestrator.trigger(source, "class Source {}")
        started.await()
        val oldJob = orchestrator.getDiagnosticJob(source)!!
        orchestrator.invalidateWorkspaceConfiguration()
        finish.complete(Unit)
        oldJob.join()
        assertTrue(publications.none { it.uri == config.toUri().toString() && it.diagnostics.isNotEmpty() })
        scope.cancel()
    }

    private fun configNotice(config: URI, hash: String): Diagnostic {
        val literal = Range(Position(0, 18), Position(0, 38))
        return Diagnostic(
            Range(Position(0, 0), Position(0, 0)),
            "Invalid ruleset",
            DiagnosticSeverity.Information,
            "gvy",
            "gvy.providerFailure",
        ).apply {
            data = mapOf("provider" to "codenarc", "configurationUri" to config.toString(), "configurationHash" to hash)
            relatedInformation =
                listOf(DiagnosticRelatedInformation(Location(config.toString(), literal), "Unknown ruleset"))
        }
    }
}
