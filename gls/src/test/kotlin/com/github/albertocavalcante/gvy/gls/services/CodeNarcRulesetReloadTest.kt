// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import com.github.albertocavalcante.gvy.diagnostics.codenarc.DefaultProjectTypeDetector
import com.github.albertocavalcante.gvy.diagnostics.codenarc.HierarchicalRulesetResolver
import com.github.albertocavalcante.gvy.diagnostics.codenarc.ProjectTypeDetector
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
