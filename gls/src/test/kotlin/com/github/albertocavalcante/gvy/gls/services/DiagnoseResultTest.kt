package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.diagnostics.codenarc.HierarchicalRulesetResolver
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DiagnoseResultTest {
    private val uri = URI.create("file:///workspace/Example.groovy")
    private val source = "class Example {}"

    @Test
    fun `provider failures reach the diagnostic result`(): Unit = runBlocking {
        val resolver = mockk<HierarchicalRulesetResolver>()
        every { resolver.resolve(any(), any()) } throws IOException("ruleset unavailable")
        val compilation = compilation(CompilationResult(true, null, emptyList(), source, parseCompleted = true))
        val service = service(compilation, resolver)

        val result = service.diagnoseResult(uri, source)

        assertFalse(result.isSuccessful)
        assertEquals("codenarc", result.failures.single().providerId)
        assertEquals("ruleset unavailable", result.failures.single().message)
    }

    @Test
    fun `incomplete compilation is an operational failure`(): Unit = runBlocking {
        val compilation = compilation(CompilationResult.failure(emptyList()))
        val service = service(compilation, mockk())
        assertFailsWith<IllegalStateException> { service.diagnoseResult(uri, source) }
    }

    private suspend fun service(
        compilation: GroovyCompilationService,
        resolver: HierarchicalRulesetResolver,
    ): GroovyTextDocumentService = GroovyTextDocumentService(
        CoroutineScope(currentCoroutineContext()),
        compilation,
        GroovyTextDocumentServiceOptions(
            serverConfiguration = ServerConfiguration().let {
                it.copy(diagnosticConfig = it.diagnosticConfig.copy(disabledProviders = setOf("custom-rules", "unused-imports")))
            },
            codeNarcRulesetResolverFactory = { resolver },
        ),
    )

    private fun compilation(result: CompilationResult): GroovyCompilationService = mockk<GroovyCompilationService>().also {
        every { it.updateServerConfiguration(any()) } just Runs
        every { it.workspaceManager } returns mockk {
            every { getWorkspaceRoot() } returns null
        }
        coEvery { it.compile(uri, source) } returns result
    }
}
