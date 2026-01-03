package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AbstractDiagnosticRuleTest {

    @Test
    fun `should execute rule when no errors`() = runBlocking {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext) =
                listOf(diagnostic(0, 0, 5, "Test diagnostic"))
        }

        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false

        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), "test", context)

        assertEquals(1, diagnostics.size)
        assertEquals("Test diagnostic", diagnostics.first().message)
    }

    @Test
    fun `should skip analysis when errors exist and rule does not allow errored code`() = runBlocking {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext) =
                listOf(diagnostic(0, 0, 5, "Should not appear"))
        }

        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns true

        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), "test", context)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `should run analysis when errors exist but rule allows errored code`() = runBlocking {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"

            override fun allowsErroredCode() = true

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext) =
                listOf(diagnostic(0, 0, 5, "Runs despite errors"))
        }

        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns true

        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), "test", context)

        assertEquals(1, diagnostics.size)
        assertEquals("Runs despite errors", diagnostics.first().message)
    }

    @Test
    fun `should handle exceptions gracefully`() = runBlocking {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> =
                error("Test exception")
        }

        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false

        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), "test", context)

        assertTrue(diagnostics.isEmpty(), "Should return empty list on exception")
    }

    @Test
    fun `should rethrow cancellation exceptions`() {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> =
                throw CancellationException("Cancelled")
        }

        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false

        assertFailsWith<CancellationException> {
            runBlocking { rule.analyze(URI.create("file:///test.groovy"), "test", context) }
        }
    }

    @Test
    fun `should create diagnostic with correct properties`() {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"
            override val defaultSeverity = DiagnosticSeverity.Error

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext) =
                listOf(diagnostic(1, 5, 10, "Test message"))
        }

        runBlocking {
            val context = mockk<RuleContext>()
            every { context.hasErrors() } returns false

            val diagnostics = rule.analyze(URI.create("file:///test.groovy"), "test", context)
            val diagnostic = diagnostics.first()

            assertEquals(1, diagnostic.range.start.line)
            assertEquals(5, diagnostic.range.start.character)
            assertEquals(1, diagnostic.range.end.line)
            assertEquals(10, diagnostic.range.end.character)
            assertEquals("Test message", diagnostic.message)
            assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
            assertEquals("groovy-lsp", diagnostic.source)
            assertEquals("H:test-rule", diagnostic.code.left)
        }
    }

    @Test
    fun `should create multi-line diagnostic`() {
        val rule = object : AbstractDiagnosticRule() {
            override val id = "test-rule"
            override val description = "Test rule"

            override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext) =
                listOf(diagnostic(0, 0, 2, 10, "Multi-line"))
        }

        runBlocking {
            val context = mockk<RuleContext>()
            every { context.hasErrors() } returns false

            val diagnostics = rule.analyze(URI.create("file:///test.groovy"), "test", context)
            val diagnostic = diagnostics.first()

            assertEquals(0, diagnostic.range.start.line)
            assertEquals(0, diagnostic.range.start.character)
            assertEquals(2, diagnostic.range.end.line)
            assertEquals(10, diagnostic.range.end.character)
        }
    }
}
