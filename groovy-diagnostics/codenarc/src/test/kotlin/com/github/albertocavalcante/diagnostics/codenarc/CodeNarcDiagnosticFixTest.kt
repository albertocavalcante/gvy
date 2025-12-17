package com.github.albertocavalcante.diagnostics.codenarc

import com.github.albertocavalcante.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for CodeNarc diagnostic conversion.
 *
 * NOTE: The triplication regression is validated using a stubbed CodeNarc `Results` tree to keep the test
 * deterministic and independent from CodeNarc runtime performance.
 */
class CodeNarcDiagnosticFixTest {

    private fun createStubAnalyzer(results: Results): CodeAnalyzer = object : CodeAnalyzer {
        override fun analyze(
            sourceCode: String,
            fileName: String,
            rulesetContent: String,
            propertiesFile: String?,
        ): Results = results
    }

    private fun createEmptyResults(): Results = mockk<Results>().also { results ->
        every { results.children } returns mutableListOf()
        every { results.violations } returns mutableListOf()
    }

    @Test
    fun `should not triplicate diagnostics when analyzing groovy code with violations`() {
        val groovyCodeWithViolations = "class TestClass {\n" +
            "    def method() {   \n" +
            "        def x = 1\n" +
            "        return x\n" +
            "    }\n" +
            "}"

        val trailingWhitespaceRule = mockk<Rule>()
        every { trailingWhitespaceRule.name } returns "TrailingWhitespace"
        every { trailingWhitespaceRule.priority } returns 3

        val duplicatedViolation = mockk<Violation>()
        every { duplicatedViolation.rule } returns trailingWhitespaceRule
        every { duplicatedViolation.lineNumber } returns 2
        every { duplicatedViolation.message } returns "Line ends with whitespace characters"

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(duplicatedViolation)

        val rootResults = mockk<Results>()
        // Intentionally reuse the same violation on both parent and leaf nodes to simulate the triplication regression.
        // Before the leaf-only traversal fix, both levels were processed, producing duplicates.
        every { rootResults.children } returns mutableListOf(leafResults)
        every { rootResults.violations } returns mutableListOf(duplicatedViolation)

        val workspaceContext = object : WorkspaceContext {
            override val root: Path? = Paths.get(".")
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        val testRulesetResolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext): RulesetConfiguration = RulesetConfiguration(
                rulesetContent = "ruleset { TrailingWhitespace }",
                propertiesFile = null,
                source = "test-ruleset",
            )
        }

        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            rulesetResolver = testRulesetResolver,
            codeAnalyzer = createStubAnalyzer(rootResults),
        )
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(groovyCodeWithViolations, "TestClass.groovy")

        assertTrue(diagnostics.isNotEmpty(), "Should detect at least one diagnostic")

        val violationsByLineAndRule = diagnostics.groupBy { "${it.range.start.line}:${it.code.left}" }
        violationsByLineAndRule.forEach { (lineAndRule, violations) ->
            assertEquals(
                1,
                violations.size,
                "Violation $lineAndRule should appear exactly once, but found ${violations.size} times",
            )
        }
    }

    @Test
    fun `should handle empty source code gracefully`() {
        val emptyResults = createEmptyResults()

        val workspaceContext = object : WorkspaceContext {
            override val root: Path? = Paths.get(".")
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(emptyResults),
        )
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics("", "empty.groovy")

        assertTrue(diagnostics.isEmpty(), "Empty source should produce no diagnostics")
    }

    @Test
    fun `should handle basic groovy code without violations`() {
        val cleanGroovyCode = """
            class CleanClass {
                def cleanMethod() {
                    return "clean"
                }
            }
        """.trimIndent()

        val workspaceContext = object : WorkspaceContext {
            override val root: Path? = Paths.get(".")
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        val emptyResults = createEmptyResults()

        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(emptyResults),
        )
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(cleanGroovyCode, "CleanClass.groovy")

        assertTrue(diagnostics.isEmpty(), "Clean code should produce no diagnostics")
    }
}
