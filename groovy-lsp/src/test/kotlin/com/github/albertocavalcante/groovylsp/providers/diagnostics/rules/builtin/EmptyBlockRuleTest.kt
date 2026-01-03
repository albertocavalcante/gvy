package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

class EmptyBlockRuleTest {

    private val rule = EmptyBlockRule()

    @Test
    fun `should detect empty block`() = runBlocking {
        val code = """
            class Example {
                void test() {}
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.first()
        assertEquals("Empty block found - consider removing or adding implementation", diagnostic.message)
        assertEquals(DiagnosticSeverity.Hint, diagnostic.severity)
    }

    @Test
    fun `should detect multiple empty blocks`() = runBlocking {
        val code = """
            class Example {
                void test1() {}
                void test2() {}
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(2, diagnostics.size)
    }

    @Test
    fun `should not flag non-empty blocks`() = runBlocking {
        val code = """
            class Example {
                void test() {
                    println("Not empty")
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not flag empty block in comment`() = runBlocking {
        val code = """
            // This is a comment with {}
            class Example {
                void test() {
                    x = 1
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///test.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    private fun mockContext(): RuleContext {
        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false
        every { context.getAst() } returns null
        return context
    }
}
