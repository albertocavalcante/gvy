package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NullSafetyRuleTest {

    private val rule = NullSafetyRule()

    @Test
    fun `should be disabled by default`() {
        assertFalse(rule.enabledByDefault, "NullSafetyRule should be disabled by default to avoid noise")
    }

    @Test
    fun `should have hint severity`() {
        assertEquals(DiagnosticSeverity.Hint, rule.defaultSeverity)
    }

    @Test
    fun `should detect unsafe method chaining`() = runBlocking {
        val code = """
            def result = list.find { it.name == 'test' }.getValue()
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertTrue(diagnostics.isNotEmpty(), "Should detect unsafe chaining")
        val diagnostic = diagnostics.first()
        assertTrue(diagnostic.message.contains("null-safe operator"))
    }

    @Test
    fun `should detect unsafe property access after method call`() = runBlocking {
        val code = """
            def name = user.findAll { it.active }.name
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertTrue(diagnostics.isNotEmpty())
    }

    @Test
    fun `should not flag code already using null-safe operator`() = runBlocking {
        val code = """
            def result = list.find { it.name == 'test' }?.getValue()
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(0, diagnostics.size, "Should not flag code already using ?.")
    }

    @Test
    fun `should not flag safe patterns`() = runBlocking {
        val code = """
            def value = myObject.getValue()
            def simple = x.y
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(0, diagnostics.size, "Should not flag simple patterns without chaining after collection methods")
    }

    @Test
    fun `should detect multiple unsafe patterns in same file`() = runBlocking {
        val code = """
            def a = list.find { it.x }.value
            def b = items.collect { it }.first
            def c = data.get(key).name
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertTrue(diagnostics.size >= 2, "Should detect multiple unsafe patterns")
    }

    @Test
    fun `should not flag patterns in strings`() = runBlocking {
        val code = """
            def message = "list.find { it }.getValue()"
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(0, diagnostics.size, "Should not flag code in strings")
    }

    @Test
    fun `should detect unsafe access after escaped backslash in string`() = runBlocking {
        val code = """
            def message = "test\\\\", def value = list.find { it }.name
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(1, diagnostics.size, "Should detect unsafe access after string literal")
    }

    @Test
    fun `should not flag patterns in comments`() = runBlocking {
        val code = """
            // This is unsafe: list.find { it }.getValue()
            def safe = getSafeValue()
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(0, diagnostics.size, "Should not flag code in comments")
    }

    private fun mockContext(): RuleContext {
        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false
        every { context.getAst() } returns null
        return context
    }
}
