@file:Suppress("ktlint:standard:function-naming")

package com.github.albertocavalcante.groovylsp.providers.codeaction

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Property-based tests for range bounds validation in LintFixAction.
 *
 * **Feature: codenarc-lint-fixes, Property 11: Range Bounds Validation**
 * **Validates: Requirements 5.2, 5.3**
 */
class RangeValidationPropertyTest {

    private val lintFixAction = LintFixAction()
    private val testUri = "file:///test.groovy"

    /**
     * Property test: Out-of-bounds line numbers return null without exception.
     *
     * **Feature: codenarc-lint-fixes, Property 11: Range Bounds Validation**
     * **Validates: Requirements 5.2, 5.3**
     *
     * For any diagnostic with a line number exceeding the source line count,
     * the system should return null (empty list) without throwing an exception.
     */
    @Property(tries = 100)
    fun `property - out of bounds line numbers return empty actions without exception`(
        @ForAll("outOfBoundsLineScenarios") scenario: OutOfBoundsScenario,
    ): Boolean {
        val diagnostic = createDiagnosticWithLine(
            ruleName = scenario.ruleName,
            line = scenario.lineNumber,
        )

        // Should not throw exception, should return empty list
        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            scenario.content,
        )

        // Out-of-bounds diagnostics should not produce any actions
        return actions.isEmpty()
    }

    /**
     * Property test: Negative line numbers return null without exception.
     *
     * **Feature: codenarc-lint-fixes, Property 11: Range Bounds Validation**
     * **Validates: Requirements 5.2, 5.3**
     *
     * For any diagnostic with a negative line number,
     * the system should return null (empty list) without throwing an exception.
     */
    @Property(tries = 100)
    fun `property - negative line numbers return empty actions without exception`(
        @ForAll("negativeLineScenarios") scenario: NegativeLineScenario,
    ): Boolean {
        val diagnostic = createDiagnosticWithLine(
            ruleName = scenario.ruleName,
            line = scenario.lineNumber,
        )

        // Should not throw exception, should return empty list
        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            scenario.content,
        )

        // Negative line diagnostics should not produce any actions
        return actions.isEmpty()
    }

    /**
     * Property test: Valid line numbers within bounds are processed.
     *
     * **Feature: codenarc-lint-fixes, Property 11: Range Bounds Validation**
     * **Validates: Requirements 5.2, 5.3**
     *
     * For any diagnostic with a valid line number within bounds,
     * the system should process it without throwing an exception.
     */
    @Property(tries = 100)
    fun `property - valid line numbers within bounds do not throw exceptions`(
        @ForAll("validLineScenarios") scenario: ValidLineScenario,
    ): Boolean {
        val diagnostic = createDiagnosticWithLine(
            ruleName = scenario.ruleName,
            line = scenario.lineNumber,
        )

        // Should not throw exception
        val actions = lintFixAction.createLintFixActions(
            testUri,
            listOf(diagnostic),
            scenario.content,
        )

        // Valid line numbers should be processed without exception
        // The result depends on whether the handler can apply a fix
        return true // No exception means success
    }

    // === Data Classes for Test Scenarios ===

    data class OutOfBoundsScenario(val content: String, val lineNumber: Int, val ruleName: String)

    data class NegativeLineScenario(val content: String, val lineNumber: Int, val ruleName: String)

    data class ValidLineScenario(val content: String, val lineNumber: Int, val ruleName: String)

    // === Providers ===

    @Provide
    fun outOfBoundsLineScenarios(): Arbitrary<OutOfBoundsScenario> {
        val contentArbitrary = Arbitraries.of(
            "",
            "def x = 1",
            "def x = 1\ndef y = 2",
            "def x = 1\ndef y = 2\ndef z = 3",
        )

        val ruleNameArbitrary = Arbitraries.of(
            "TrailingWhitespace",
            "UnnecessarySemicolon",
            "ConsecutiveBlankLines",
            "UnusedImport",
        )

        return Combinators.combine(contentArbitrary, ruleNameArbitrary)
            .`as` { content, ruleName ->
                val lineCount = content.lines().size
                // Generate line number that is >= lineCount (out of bounds)
                val outOfBoundsLine = lineCount + Arbitraries.integers().between(0, 100).sample()
                OutOfBoundsScenario(content, outOfBoundsLine, ruleName)
            }
    }

    @Provide
    fun negativeLineScenarios(): Arbitrary<NegativeLineScenario> {
        val contentArbitrary = Arbitraries.of(
            "def x = 1",
            "def x = 1\ndef y = 2",
            "def x = 1\ndef y = 2\ndef z = 3",
        )

        val negativeLineArbitrary = Arbitraries.integers().between(-1000, -1)

        val ruleNameArbitrary = Arbitraries.of(
            "TrailingWhitespace",
            "UnnecessarySemicolon",
            "ConsecutiveBlankLines",
            "UnusedImport",
        )

        return Combinators.combine(contentArbitrary, negativeLineArbitrary, ruleNameArbitrary)
            .`as` { content, line, ruleName ->
                NegativeLineScenario(content, line, ruleName)
            }
    }

    @Provide
    fun validLineScenarios(): Arbitrary<ValidLineScenario> = Arbitraries.of(
        "def x = 1   ",
        "def x = 1   \ndef y = 2   ",
        "def x = 1   \ndef y = 2   \ndef z = 3   ",
    ).flatMap { content ->
        val lineCount = content.lines().size
        val validLineArbitrary = Arbitraries.integers().between(0, lineCount - 1)
        val ruleNameArbitrary = Arbitraries.of("TrailingWhitespace")

        Combinators.combine(validLineArbitrary, ruleNameArbitrary)
            .`as` { line, ruleName ->
                ValidLineScenario(content, line, ruleName)
            }
    }

    // === Helper Methods ===

    private fun createDiagnosticWithLine(
        ruleName: String,
        line: Int,
        startChar: Int = 0,
        endChar: Int = 10,
    ): Diagnostic = Diagnostic().apply {
        range = Range(Position(line, startChar), Position(line, endChar))
        message = "Test diagnostic for $ruleName"
        source = "CodeNarc"
        code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(ruleName)
        severity = DiagnosticSeverity.Warning
    }
}
