// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import com.github.albertocavalcante.gvy.diagnostics.codenarc.DefaultProjectTypeDetector
import com.github.albertocavalcante.gvy.diagnostics.codenarc.HierarchicalRulesetResolver
import com.github.albertocavalcante.gvy.diagnostics.codenarc.ProjectTypeDetector
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The CodeNarc reload command (sent when `.codenarc` files change) clears the resolver's cached detection (#1236). */
class CodeNarcRulesetReloadTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `reloading CodeNarc rulesets clears the provider's cached project detection`(): Unit = runTest {
        Files.writeString(root.resolve("Jenkinsfile"), "pipeline { agent any }\n")
        val detections = AtomicInteger()
        var created: HierarchicalRulesetResolver? = null
        val documents = DocumentProvider()
        val service = GroovyTextDocumentService(
            coroutineScope = backgroundScope,
            compilationService = mockk<GroovyCompilationService>(relaxed = true),
            options = GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                codeNarcRulesetResolverFactory = { isJenkinsPipelineFile ->
                    HierarchicalRulesetResolver(
                        countingDetector(detections),
                        isJenkinsPipelineFile = isJenkinsPipelineFile,
                    )
                        .also { created = it }
                },
            ),
        )
        documents.put(root.resolve("App.groovy").toUri(), "class App {}\n")
        service.rerunDiagnosticsOnOpenFiles() // builds the diagnostics providers
        val resolver = assertNotNull(created, "the service builds its resolver through the factory")
        val app = root.resolve("App.groovy").toUri()
        resolver.resolve(context(), app)
        resolver.resolve(context(), app)
        val beforeReload = detections.get()

        service.reloadCodeNarcRulesets()
        resolver.resolve(context(), app)

        assertEquals(1, beforeReload)
        assertEquals(2, detections.get())
    }

    @Test
    fun `two open sources report one rejected configuration each and recover without popup`(): Unit = runTest {
        val ruleset = root.resolve(".codenarc")
        Files.writeString(ruleset, "ruleset { ruleset('rulesets/empty.xml') }")
        val compilation = mockk<GroovyCompilationService>(relaxed = true)
        every { compilation.workspaceManager.getWorkspaceRoot() } returns root
        coEvery { compilation.compile(any(), any(), any()) } returns CompilationResult.failure(emptyList())
        val client = mockk<LanguageClient>(relaxed = true)
        val documents = DocumentProvider()
        val service = GroovyTextDocumentService(
            coroutineScope = backgroundScope,
            compilationService = compilation,
            options = GroovyTextDocumentServiceOptions(documentProvider = documents, client = { client }),
        )
        val sources = listOf("First", "Second").map { name ->
            root.resolve("$name.groovy").toUri() to "class $name {}\n"
        }
        sources.forEach { (uri, content) -> documents.put(uri, content) }
        sources.forEach { (uri, content) ->
            val notices = service.diagnose(uri, content).filter { it.code?.left == "gvy.providerFailure" }
            assertEquals(1, notices.size)
            assertEquals(ruleset.toUri().toString(), notices.single().relatedInformation.single().location.uri)
        }
        verify(exactly = 0) { client.showMessage(any()) }

        Files.writeString(ruleset, "ruleset { ruleset('rulesets/basic.xml') }")
        service.reloadCodeNarcRulesets()
        sources.forEach { (uri, content) ->
            assertEquals(0, service.diagnose(uri, content).count { it.code?.left == "gvy.providerFailure" })
        }
        verify(exactly = 0) { client.showMessage(any()) }
    }

    @Test
    fun `published notices clear for both open sources after ruleset correction`(): Unit = runTest {
        val ruleset = root.resolve(".codenarc")
        Files.writeString(ruleset, "ruleset { ruleset('rulesets/empty.xml') }")
        val compilation = mockk<GroovyCompilationService>(relaxed = true)
        every { compilation.workspaceManager.getWorkspaceRoot() } returns root
        coEvery { compilation.compileAsync(any(), any(), any()) } answers {
            val content = thirdArg<String>()
            CompletableDeferred(
                CompilationResult(false, null, emptyList(), sourceText = content, parseCompleted = true),
            )
        }
        val publications = CopyOnWriteArrayList<PublishDiagnosticsParams>()
        val client = mockk<LanguageClient>(relaxed = true)
        every { client.publishDiagnostics(capture(publications)) } just Runs
        val service = GroovyTextDocumentService(
            coroutineScope = backgroundScope,
            compilationService = compilation,
            options = GroovyTextDocumentServiceOptions(client = { client }),
        )
        val sources = listOf("First", "Second").map { name ->
            root.resolve("$name.groovy").toUri() to "class $name {}\n"
        }
        sources.forEach { (uri, text) ->
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri.toString(), "groovy", 1, text)))
        }
        sources.forEach { (uri, _) -> service.awaitCurrentDiagnostics(uri, 1) }
        sources.forEach { (uri, _) ->
            val notice = publications.last { it.uri == uri.toString() }.diagnostics
                .filter { it.code?.left == "gvy.providerFailure" }
            assertEquals(1, notice.size)
            assertEquals(ruleset.toUri().toString(), notice.single().relatedInformation.single().location.uri)
        }
        verify(exactly = 0) { client.showMessage(any()) }
        Files.writeString(ruleset, "ruleset { ruleset('rulesets/basic.xml') }")
        service.reloadCodeNarcRulesets()
        service.rerunDiagnosticsOnOpenFiles()
        sources.forEach { (uri, _) -> service.awaitCurrentDiagnostics(uri, 1) }
        sources.forEach { (uri, _) ->
            assertEquals(
                0,
                publications.last { it.uri == uri.toString() }.diagnostics.count {
                    it.code?.left ==
                        "gvy.providerFailure"
                },
            )
        }
        verify(exactly = 0) { client.showMessage(any()) }
    }

    private fun countingDetector(detections: AtomicInteger): ProjectTypeDetector = object : ProjectTypeDetector {
        private val real = DefaultProjectTypeDetector()
        override fun detect(workspaceRoot: Path) = real.detect(workspaceRoot).also { detections.incrementAndGet() }
        override fun detectBuildType(workspaceRoot: Path) = real.detectBuildType(workspaceRoot)
        override fun isSharedLibrary(workspaceRoot: Path) = real.isSharedLibrary(workspaceRoot)
    }

    private fun context() = object : WorkspaceContext {
        override val root: Path = this@CodeNarcRulesetReloadTest.root
        override fun getConfiguration() = object : DiagnosticConfiguration {
            override val isEnabled = true
            override val propertiesFile: String? = null
            override val autoDetectConfig = true
        }
    }
}
