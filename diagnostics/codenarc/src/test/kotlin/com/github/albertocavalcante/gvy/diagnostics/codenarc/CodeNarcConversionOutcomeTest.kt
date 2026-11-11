package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CodeNarcConversionOutcomeTest {
    @TestFactory
    fun `conversion faults cannot become empty successful diagnostics`(): List<DynamicTest> = listOf(
        IOException("rule metadata unreadable"),
        CancellationException("conversion cancelled"),
        LinkageError("rule metadata unavailable"),
    ).map { failure ->
        DynamicTest.dynamicTest(failure.javaClass.simpleName) {
            val rule = mockk<Rule>()
            every { rule.name } throws failure
            val provider = provider(violation(rule))

            assertSame(failure, assertFails { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) })
        }
    }

    @TestFactory
    fun `out of document violation lines fail the provider`(): List<DynamicTest> =
        listOf(Int.MIN_VALUE, 0, 2).map { line ->
            DynamicTest.dynamicTest(line.toString()) {
                val provider = provider(violation(rule(), line))

                assertFailsWith<IllegalStateException> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) }
            }
        }

    @Test
    fun `a file level violation with no line remains a valid diagnostic`() {
        val diagnostics = provider(violation(rule(), line = null)).analyzeAndGetDiagnostics(SOURCE, FILE_NAME)

        assertEquals(1, diagnostics.size)
        assertEquals(0, diagnostics.single().range.start.line)
        assertEquals("LineLength", diagnostics.single().code.left)
    }

    private fun rule(): Rule = mockk<Rule>().also {
        every { it.name } returns "LineLength"
        every { it.priority } returns 3
    }

    private fun violation(rule: Rule, line: Int? = 1): Violation = mockk<Violation>().also {
        every { it.rule } returns rule
        every { it.lineNumber } returns line
        every { it.message } returns "test violation"
    }

    private fun provider(violation: Violation): CodeNarcDiagnosticProvider {
        val results = mockk<Results>()
        every { results.children } returns mutableListOf()
        every { results.violations } returns mutableListOf(violation)
        val analyzer = object : CodeAnalyzer {
            override fun analyze(
                sourceCode: String,
                fileName: String,
                rulesetContent: String,
                propertiesFile: String?,
            ): Results = results
        }
        val workspace = object : WorkspaceContext {
            override val root: Path? = null
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = false
            }
        }
        val resolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext) = RulesetConfiguration("ruleset { LineLength }")
        }
        return CodeNarcDiagnosticProvider(workspace, rulesetResolver = resolver, codeAnalyzer = analyzer)
    }

    private companion object {
        const val SOURCE = "class Example {}"
        const val FILE_NAME = "Example.groovy"
    }
}
