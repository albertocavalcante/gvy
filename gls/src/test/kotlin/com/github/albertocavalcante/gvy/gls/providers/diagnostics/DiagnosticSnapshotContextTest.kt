// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.CustomRulesProvider
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules.RuleContext
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import com.github.albertocavalcante.nativeapi.AnnotationCoverage
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagnosticSnapshotContextTest {
    private val uri = URI.create("file:///workspace/Example.groovy")
    private val signal = Diagnostic(Range(Position(0, 0), Position(0, 1)), "held analysis")
    private val parserError = Diagnostic(Range(Position(0, 0), Position(0, 1)), "syntax error").apply {
        severity = DiagnosticSeverity.Error
    }

    @Test
    fun `each legacy flow collection obtains its own current AST`(): Unit = runBlocking {
        var current = module()
        val first = current
        val compilation = compilation()
        every { compilation.getAst(uri) } answers { current }
        val observed = mutableListOf<Any?>()
        val provider = CustomRulesProvider(listOf(rule { observed.add(it.getAst()) }), compilation)
        val stream = provider.provideDiagnostics(uri, SOURCE)

        stream.toList()
        current = module()
        stream.toList()

        assertSame(first, observed.first())
        assertSame(current, observed.last())
    }

    @Test
    fun `custom rules use the held AST after the URI cache is evicted`(): Unit = runBlocking {
        val held = analysis()
        val compilation = compilation()
        every { compilation.getAst(uri) } throws IOException("URI cache evicted")
        var observed: Any? = null
        val rule = rule { observed = it.getAst() }
        val service = DiagnosticsService(listOf(CustomRulesProvider(listOf(rule), compilation)))

        val result = service.collectDiagnostics(uri, SOURCE, held)

        assertTrue(result.isSuccessful)
        assertEquals(listOf(signal), result.diagnostics)
        assertSame(held.ast, observed)
        verify(exactly = 0) { compilation.getAst(any()) }
        verify(exactly = 0) { compilation.getDiagnostics(any()) }
    }

    @Test
    fun `later parser errors cannot suppress a rule for the clean held snapshot`(): Unit = runBlocking {
        val held = analysis()
        val compilation = compilation(held.ast)
        every { compilation.getDiagnostics(uri) } returns listOf(parserError)
        val service = DiagnosticsService(listOf(CustomRulesProvider(listOf(rule {}), compilation)))

        val result = service.collectDiagnostics(uri, SOURCE, held)

        assertEquals(listOf(signal), result.diagnostics)
        assertTrue(result.isSuccessful)
        verify(exactly = 0) { compilation.getDiagnostics(any()) }
    }

    @Test
    fun `completed syntax errors retain their legitimate rule skip after the URI cache changes`(): Unit = runBlocking {
        val compilation = compilation()
        var invoked = false
        val provider = CustomRulesProvider(listOf(rule { invoked = true }), compilation)
        val held = analysis(ast = null, diagnostics = listOf(parserError), successful = false)

        val result = DiagnosticsService(listOf(provider)).collectDiagnostics(uri, SOURCE, held)

        assertTrue(result.isSuccessful)
        assertTrue(result.diagnostics.isEmpty())
        assertFalse(invoked)
        verify(exactly = 0) { compilation.getDiagnostics(any()) }
    }

    @Test
    fun `unused import detection uses the held AST after the URI cache is evicted`(): Unit = runBlocking {
        val compilation = compilation()
        every { compilation.getAst(uri) } throws IOException("URI cache evicted")
        val service = DiagnosticsService(listOf(UnusedImportDiagnosticProvider(compilation)))
        val parsed = GroovyParserFacade().parse(ParseRequest(uri, SOURCE))
        parsed.compilationUnit.classLoader.use {
            assertTrue(parsed.isSuccessful)
            assertEquals(AnnotationCoverage.Complete, parsed.sourceAnnotations.coverage)
            val held = CompilationResult(
                true,
                assertNotNull(parsed.ast),
                emptyList(),
                SOURCE,
                parseCompleted = true,
                sourceAnnotations = parsed.sourceAnnotations,
            )

            val result = service.collectDiagnostics(uri, SOURCE, held)

            assertTrue(result.isSuccessful)
            assertEquals(1, result.diagnostics.size)
            assertTrue(result.diagnostics.single().message.contains("java.util.List"))
            verify(exactly = 0) { compilation.getAst(any()) }
            verify(exactly = 0) { compilation.getCompiledSnapshot(any()) }
        }
    }

    @Test
    fun `a successful compilation missing the required AST is a failed provider`(): Unit = runBlocking {
        val compilation = compilation(ast = null)
        val service = DiagnosticsService(listOf(UnusedImportDiagnosticProvider(compilation)))

        val result = service.collectDiagnostics(uri, SOURCE, analysis(ast = null))

        assertFalse(result.isSuccessful)
        assertEquals("unused-imports", result.failures.single().providerId)
        assertEquals(IllegalStateException::class.java.name, result.failures.single().exceptionType)
    }

    @Test
    fun `a completed syntax failure can skip unused imports without an AST`(): Unit = runBlocking {
        val compilation = compilation(ast = null)
        val service = DiagnosticsService(listOf(UnusedImportDiagnosticProvider(compilation)))

        val result = service.collectDiagnostics(
            uri,
            SOURCE,
            analysis(ast = null, diagnostics = listOf(parserError), successful = false),
        )

        assertTrue(result.isSuccessful)
        assertTrue(result.diagnostics.isEmpty())
        verify(exactly = 0) { compilation.getAst(any()) }
    }

    @Test
    fun `a source only provider preserves its legacy route without compilation access`(): Unit = runBlocking {
        val provider = object : StreamingDiagnosticProvider {
            override val id = "source-only"
            override suspend fun provideDiagnostics(uri: URI, content: String) = flowOf(signal)
        }

        val result = DiagnosticsService(listOf(provider)).collectDiagnostics(uri, SOURCE, analysis())

        assertEquals(listOf(signal), result.diagnostics)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `a held result for different source cannot certify collection`(): Unit = runBlocking {
        val service = DiagnosticsService(emptyList())

        assertFailsWith<IllegalArgumentException> {
            service.collectDiagnostics(uri, SOURCE, analysis().copy(sourceText = "class Other {}"))
        }
    }

    @Test
    fun `a held result without source provenance cannot certify collection`(): Unit = runBlocking {
        val service = DiagnosticsService(emptyList())

        assertFailsWith<IllegalArgumentException> {
            service.collectDiagnostics(uri, SOURCE, analysis().copy(sourceText = null))
        }
    }

    @Test
    fun `incomplete parsing cannot certify collection`(): Unit = runBlocking {
        val service = DiagnosticsService(emptyList())

        assertFailsWith<IllegalStateException> {
            service.collectDiagnostics(uri, SOURCE, analysis().copy(parseCompleted = false))
        }
    }

    private fun rule(observe: (RuleContext) -> Unit): AbstractDiagnosticRule = object : AbstractDiagnosticRule() {
        override val id = "snapshot-rule"
        override val description = "Reads the held analysis"
        override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
            observe(context)
            return listOf(signal)
        }
    }

    private fun compilation(ast: ASTNode? = module()): GroovyCompilationService =
        mockk<GroovyCompilationService>().also {
            every { it.getAst(uri) } returns ast
            every { it.getDiagnostics(uri) } returns emptyList()
        }

    private fun analysis(
        ast: ASTNode? = module(),
        diagnostics: List<Diagnostic> = emptyList(),
        successful: Boolean = true,
    ) = CompilationResult(successful, ast, diagnostics, SOURCE, parseCompleted = true)

    private fun module(): ModuleNode = checkNotNull(
        GroovyParserFacade().parse(ParseRequest(uri, SOURCE)).ast,
    )

    private companion object {
        const val SOURCE = "import java.util.List\nclass Example {}"
    }
}
