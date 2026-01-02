package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomRulesProviderTest {

    @Test
    fun `should emit diagnostics from all rules`() = runBlocking {
        val rule1 = createMockRule("rule-1", listOf(createDiagnostic("Rule 1 violation")))
        val rule2 = createMockRule("rule-2", listOf(createDiagnostic("Rule 2 violation")))

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getAst(any()) } returns null
        every { compilationService.getDiagnostics(any()) } returns emptyList()

        val provider = CustomRulesProvider(listOf(rule1, rule2), compilationService)
        val uri = URI.create("file:///test.groovy")
        val diagnostics = provider.provideDiagnostics(uri, "test code").toList()

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.any { it.message == "Rule 1 violation" })
        assertTrue(diagnostics.any { it.message == "Rule 2 violation" })
    }

    @Test
    fun `should skip disabled rules`() = runBlocking {
        val enabledRule = createMockRule("enabled", listOf(createDiagnostic("Enabled")), enabled = true)
        val disabledRule = createMockRule("disabled", listOf(createDiagnostic("Disabled")), enabled = false)

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getAst(any()) } returns null
        every { compilationService.getDiagnostics(any()) } returns emptyList()

        val provider = CustomRulesProvider(listOf(enabledRule, disabledRule), compilationService)
        val uri = URI.create("file:///test.groovy")
        val diagnostics = provider.provideDiagnostics(uri, "test code").toList()

        assertEquals(1, diagnostics.size)
        assertEquals("Enabled", diagnostics.first().message)
    }

    @Test
    fun `should handle rule exceptions gracefully`() = runBlocking {
        val goodRule = createMockRule("good", listOf(createDiagnostic("Good rule")))
        val badRule = object : AbstractDiagnosticRule() {
            override val id = "bad"
            override val description = "Bad rule"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): Nothing =
                throw RuntimeException("Rule failed")
        }

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getAst(any()) } returns null
        every { compilationService.getDiagnostics(any()) } returns emptyList()

        val provider = CustomRulesProvider(listOf(goodRule, badRule), compilationService)
        val uri = URI.create("file:///test.groovy")
        val diagnostics = provider.provideDiagnostics(uri, "test code").toList()

        // Should still get diagnostics from good rule
        assertEquals(1, diagnostics.size)
        assertEquals("Good rule", diagnostics.first().message)
    }

    @Test
    fun `should create context with AST access`() = runBlocking {
        val mockAst = mockk<Any>()
        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getAst(any()) } returns mockAst
        every { compilationService.getDiagnostics(any()) } returns emptyList()

        var contextReceived: RuleContext? = null
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test"
            override val description = "Test"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
                contextReceived = context
                return emptyList()
            }
        }

        val provider = CustomRulesProvider(listOf(rule), compilationService)
        val uri = URI.create("file:///test.groovy")
        provider.provideDiagnostics(uri, "test").toList()

        // Verify context was provided and AST is accessible
        assertTrue(contextReceived != null)
        assertEquals(mockAst, contextReceived?.getAst())
    }

    @Test
    fun `should detect errors in context`() = runBlocking {
        val errorDiagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 1)),
            "Error",
            DiagnosticSeverity.Error,
            "test",
        )

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getAst(any()) } returns null
        every { compilationService.getDiagnostics(any()) } returns listOf(errorDiagnostic)

        var hasErrors = false
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test"
            override val description = "Test"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
                hasErrors = context.hasErrors()
                return emptyList()
            }

            override fun allowsErroredCode() = true
        }

        val provider = CustomRulesProvider(listOf(rule), compilationService)
        val uri = URI.create("file:///test.groovy")
        provider.provideDiagnostics(uri, "test").toList()

        assertTrue(hasErrors)
    }

    @Test
    fun `should have correct provider metadata`() {
        val compilationService = mockk<GroovyCompilationService>()
        val provider = CustomRulesProvider(emptyList(), compilationService)

        assertEquals("custom-rules", provider.id)
        assertTrue(provider.enabledByDefault)
    }

    private fun createMockRule(id: String, diagnostics: List<Diagnostic>, enabled: Boolean = true): DiagnosticRule {
        val rule = mockk<DiagnosticRule>()
        every { rule.id } returns id
        every { rule.description } returns "Test rule"
        every { rule.enabledByDefault } returns enabled
        every { rule.defaultSeverity } returns DiagnosticSeverity.Warning
        every { runBlocking { rule.analyze(any(), any(), any()) } } returns diagnostics
        return rule
    }

    private fun createDiagnostic(message: String): Diagnostic = Diagnostic(
        Range(Position(0, 0), Position(0, 1)),
        message,
        DiagnosticSeverity.Warning,
        "test",
    )
}
