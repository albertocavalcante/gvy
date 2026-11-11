package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.CustomRulesProvider
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.DiagnosticRule
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.builtin.JenkinsPipelineStageRule
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.jenkins.JenkinsFileDetector
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Diagnostic
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticModeContextTest {
    private val uri = URI.create("file:///workspace/build.pipeline")
    private val held = CompilationResult(true, null, emptyList(), SOURCE, parseCompleted = true)

    @Test
    fun `held Groovy mode cannot become Jenkins after live configuration changes`(): Unit = runBlocking {
        assertMode(configuration(GroovyMode.GROOVY), configuration(GroovyMode.JENKINS), false)
    }

    @Test
    fun `held Jenkins mode cannot become Groovy after live configuration changes`(): Unit = runBlocking {
        assertMode(configuration(GroovyMode.JENKINS), configuration(GroovyMode.GROOVY), true)
    }

    @Test
    fun `held automatic patterns survive a later live configuration`(): Unit = runBlocking {
        assertMode(configuration(GroovyMode.AUTO, listOf("*.pipeline")), configuration(GroovyMode.GROOVY), true)
    }

    @Test
    fun `held automatic patterns reject a file enabled only by later configuration`(): Unit = runBlocking {
        assertMode(configuration(GroovyMode.AUTO, listOf("Jenkinsfile")), configuration(GroovyMode.JENKINS), false)
    }

    @Test
    fun `the captured pattern classifier cannot change when its input list is cleared`() {
        val patterns = mutableListOf("*.pipeline")
        val detector = JenkinsFileDetector(patterns)
        patterns.clear()

        assertTrue(detector.isJenkinsFile(uri))
    }

    @Test
    fun `legacy callers without an explicit configuration still resolve live mode`(): Unit = runBlocking {
        var current = configuration(GroovyMode.GROOVY)
        val compilation = compilation { current }
        val provider = CustomRulesProvider(listOf(modeRule()), compilation)

        assertEquals("false", provider.provideDiagnostics(uri, SOURCE).toList().single().message)
        current = configuration(GroovyMode.JENKINS)
        assertEquals("true", provider.provideDiagnostics(uri, SOURCE).toList().single().message)
    }

    @Test
    fun `the service factory retains Groovy mode when compilation changes live settings`(): Unit = runBlocking {
        assertServiceMode(GroovyMode.GROOVY, GroovyMode.JENKINS, 0)
    }

    @Test
    fun `the service factory retains Jenkins mode when compilation changes live settings`(): Unit = runBlocking {
        assertServiceMode(GroovyMode.JENKINS, GroovyMode.GROOVY, 1)
    }

    private suspend fun assertServiceMode(capturedMode: GroovyMode, laterMode: GroovyMode, expectedCount: Int) {
        val captured = configuration(capturedMode).let { base ->
            base.copy(
                diagnosticConfig = base.diagnosticConfig.copy(disabledProviders = setOf("codenarc", "unused-imports")),
            )
        }
        var current = captured
        val compilation = compilation { current }
        every { compilation.updateServerConfiguration(any()) } just Runs
        val source = """
            pipeline {
                stages {
                    stage('Build') {
                        echo 'Building'
                    }
                }
            }
        """.trimIndent()
        val ast = SourceUnit.create("build.pipeline", source).run {
            parse()
            completePhase()
            convert()
            ast
        }
        val fixtureContext = object : RuleContext {
            override fun getAst(): Any = ast
            override fun hasErrors(): Boolean = false
            override fun isJenkinsFile(uri: URI): Boolean = true
        }
        assertEquals(
            1,
            JenkinsPipelineStageRule().analyze(uri, source, fixtureContext).size,
            "The AST fixture must report",
        )
        val result = CompilationResult(true, ast, emptyList(), source, parseCompleted = true)
        coEvery { compilation.compile(uri, source) } answers {
            current = configuration(laterMode)
            result
        }
        every { compilation.getAst(uri) } returns ast
        every { compilation.getDiagnostics(uri) } returns emptyList()
        val service = GroovyTextDocumentService(
            CoroutineScope(currentCoroutineContext()),
            compilation,
            GroovyTextDocumentServiceOptions(serverConfiguration = captured),
        )

        val diagnostics = service.diagnose(uri, source)

        assertEquals(expectedCount, diagnostics.size)
        assertTrue(diagnostics.all { it.code?.left == "A:jenkins-stage-structure" })
    }

    private suspend fun assertMode(captured: ServerConfiguration, live: ServerConfiguration, expected: Boolean) {
        val compilation = compilation { live }
        val provider = CustomRulesProvider(listOf(modeRule()), compilation, configuration = captured)

        val result = DiagnosticsService(listOf(provider)).collectDiagnostics(uri, SOURCE, held)

        assertTrue(result.isSuccessful, "The held configuration must not require live access: ${result.failures}")
        assertEquals(expected.toString(), result.diagnostics.single().message)
        verify(exactly = 0) { compilation.serverConfiguration }
        verify(exactly = 0) { compilation.workspaceManager }
    }

    private fun compilation(configuration: () -> ServerConfiguration): GroovyCompilationService =
        mockk<GroovyCompilationService>().also {
            every { it.serverConfiguration } answers { configuration() }
            every { it.workspaceManager } returns mockk {
                every { getJenkinsCapabilities() } returns null
                every { getWorkspaceRoot() } returns null
            }
        }

    private fun modeRule(): DiagnosticRule = object : DiagnosticRule {
        override val id = "mode-context"
        override val description = "Reports the context's effective Jenkins mode"
        override suspend fun analyze(uri: URI, content: String, context: RuleContext): List<Diagnostic> =
            listOf(Diagnostic().apply { message = context.isJenkinsFile(uri).toString() })
    }

    private fun configuration(mode: GroovyMode, patterns: List<String> = listOf("Jenkinsfile")): ServerConfiguration {
        val base = ServerConfiguration()
        return base.copy(groovyMode = mode, jenkinsConfig = base.jenkinsConfig.copy(filePatterns = patterns))
    }

    private companion object {
        const val SOURCE = "class Example {}"
    }
}
