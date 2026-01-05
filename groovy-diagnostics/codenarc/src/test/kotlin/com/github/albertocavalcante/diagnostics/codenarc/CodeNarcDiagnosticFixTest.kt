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
        every { duplicatedViolation.sourceLine } returns null // Stub sourceLine to avoid strict mock failure

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

    // ==========================================
    // Range Calculation Tests
    // ==========================================

    @Test
    fun `should use violation sourceLine to find precise column for UnnecessarySemicolon`() {
        // sourceLine: "def x = 1;"
        // The semicolon is at column 10 (0-based: character 9)
        val sourceCode = "class Test {\n    def x = 1;\n}"

        val rule = mockk<Rule>()
        every { rule.name } returns "UnnecessarySemicolon"
        every { rule.priority } returns 3

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 2
        every { violation.message } returns "Semicolon at end of line"
        every { violation.sourceLine } returns "    def x = 1;"

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(violation)

        val workspaceContext = createTestWorkspaceContext()
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(sourceCode, "Test.groovy")

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]
        // Semicolon is at index 13 in "    def x = 1;" (0-based)
        // Range should highlight just the semicolon
        assertEquals(13, diag.range.start.character, "Start should be at semicolon position")
        assertEquals(14, diag.range.end.character, "End should be after semicolon (exclusive)")
    }

    @Test
    fun `should use violation sourceLine to find precise range for TrailingWhitespace`() {
        // sourceLine: "def x = 1   " (3 trailing spaces)
        val sourceCode = "class Test {\n    def x = 1   \n}"

        val rule = mockk<Rule>()
        every { rule.name } returns "TrailingWhitespace"
        every { rule.priority } returns 3

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 2
        every { violation.message } returns "Line ends with whitespace characters"
        every { violation.sourceLine } returns "    def x = 1   "

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(violation)

        val workspaceContext = createTestWorkspaceContext()
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(sourceCode, "Test.groovy")

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]
        // "    def x = 1" is 13 chars, then 3 spaces = total 16
        // Trailing whitespace starts at index 13
        assertEquals(13, diag.range.start.character, "Start should be at beginning of trailing whitespace")
        assertEquals(16, diag.range.end.character, "End should be at end of line (exclusive)")
    }

    @Test
    fun `should extract variable name from UnusedVariable message and find in sourceLine`() {
        // Message: "The variable [unusedVar] in class Test is not used"
        // sourceLine: "def unusedVar = 123"
        val sourceCode = "class Test {\n    def unusedVar = 123\n}"

        val rule = mockk<Rule>()
        every { rule.name } returns "UnusedVariable"
        every { rule.priority } returns 2

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 2
        every { violation.message } returns "The variable [unusedVar] in class Test is not used"
        every { violation.sourceLine } returns "    def unusedVar = 123"

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(violation)

        val workspaceContext = createTestWorkspaceContext()
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(sourceCode, "Test.groovy")

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]
        // "unusedVar" starts at index 8 in "    def unusedVar = 123"
        assertEquals(8, diag.range.start.character, "Start should be at variable name")
        assertEquals(17, diag.range.end.character, "End should be after variable name (exclusive)")
    }

    @Test
    fun `should handle null sourceLine gracefully and fall back to heuristics`() {
        val sourceCode = "class Test {\n    def x = 1\n}"

        val rule = mockk<Rule>()
        every { rule.name } returns "SomeRule"
        every { rule.priority } returns 2

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 2
        every { violation.message } returns "Some violation"
        every { violation.sourceLine } returns null // sourceLine is null

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(violation)

        val workspaceContext = createTestWorkspaceContext()
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(sourceCode, "Test.groovy")

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]
        // Should fall back to highlighting the full line or default heuristic
        // RuleRangeCalculator default range skips leading whitespace (indentation is 4 spaces)
        assertEquals(4, diag.range.start.character, "Should start at column 4 (skipping whitespace)")
        // Smart fallback highlights the first word ("def") instead of the whole line
        assertEquals(7, diag.range.end.character, "End should be at end of first word")
    }

    @Test
    fun `should handle escaped quotes in GString violations`() {
        // sourceLine: def s = "escaped \" quote"
        val sourceCode = "class Test {\n    def s = \"escaped \\\" quote\"\n}"

        val rule = mockk<Rule>()
        every { rule.name } returns "UnnecessaryGString"
        every { rule.priority } returns 3

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 2
        every { violation.message } returns "The String \"escaped \" quote\" can be a single quoted string"
        every { violation.sourceLine } returns "    def s = \"escaped \\\" quote\""

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(violation)

        val workspaceContext = createTestWorkspaceContext()
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(sourceCode, "Test.groovy")

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // "    def s = " -> length 12
        // GString starts at index 12
        // Content: "escaped \" quote" -> length 16 + 2 quotes + 1 backslash = 19?
        // Wait, source line literal: "    def s = \"escaped \\\" quote\""
        // Indices:
        // 0123456789012
        //     def s = "escaped \" quote"
        //             ^ start at 12
        //                             ^ end at 12 + 19 = 31?
        // Let's rely on the calculator logic: startQuote (12), endQuote (30, exclusive)

        assertEquals(12, diag.range.start.character, "Start should be at opening quote")
        // The closing quote is at index 29 (inclusive), so end 30 (exclusive).
        assertEquals(30, diag.range.end.character, "End should be after closing quote")
    }

    @Test
    fun `should handle empty line violations with zero length range`() {
        val sourceCode = "" // Empty file, violation on line 1

        val rule = mockk<Rule>()
        every { rule.name } returns "SomeRule"
        every { rule.priority } returns 2

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.lineNumber } returns 1
        every { violation.message } returns "Violation on empty line"
        every { violation.sourceLine } returns "" // Empty source line

        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns mutableListOf(violation)

        val workspaceContext = createTestWorkspaceContext()
        // We need to allow empty source analysis for this test
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        // Force analyzing " " to trigger logic but simulating empty line in violation
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(" ", "Test.groovy")

        // Let's pass a newline so there is a line, but it's empty.
        val diagnostics2 = diagnosticProvider.analyzeAndGetDiagnostics("\n", "Test.groovy")

        assertEquals(1, diagnostics2.size)
        val diag = diagnostics2[0]

        assertEquals(0, diag.range.start.character)
        assertEquals(0, diag.range.end.character, "Range on empty line should be (0, 0)")
    }

    private fun createTestWorkspaceContext(): WorkspaceContext = object : WorkspaceContext {
        override val root: Path? = Paths.get(".")
        override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
            override val isEnabled: Boolean = true
            override val propertiesFile: String? = null
            override val autoDetectConfig: Boolean = false
        }
    }
}
