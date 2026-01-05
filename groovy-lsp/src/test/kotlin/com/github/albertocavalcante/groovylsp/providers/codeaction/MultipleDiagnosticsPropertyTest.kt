@file:Suppress("ktlint:standard:function-naming")

package com.github.albertocavalcante.groovylsp.providers.codeaction

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Property-based tests for multiple diagnostics handling.
 *
 * **Feature: codenarc-lint-fixes, Property 13: Multiple Diagnostics Handling**
 * **Validates: Requirements 6.4**
 */
class MultipleDiagnosticsPropertyTest {

    private val lintFixAction = LintFixAction()
    private val testUri = "file:///test.groovy"

    /**
     * Property test: Multiple Diagnostics Handling
     * **Feature: codenarc-lint-fixes, Property 13: Multiple Diagnostics Handling**
     * **Validates: Requirements 6.4**
     *
     * For any list of N diagnostics where M have registered handlers,
     * the system should return at most M CodeActions.
     *
     * Note: The actual count may be less than M if handlers return null
     * for specific content/diagnostic combinations.
     */
    @Property(tries = 100)
    fun `property - returns at most M actions for M diagnostics with handlers`(
        @ForAll("diagnosticLists") diagnosticList: DiagnosticList,
    ): Boolean {
        val (diagnostics, content, expectedMaxActions) = diagnosticList

        val actions = lintFixAction.createLintFixActions(testUri, diagnostics, content)

        // The number of actions should be at most the number of diagnostics with registered handlers
        return actions.size <= expectedMaxActions
    }

    /**
     * Property test: Each Action Corresponds to a Diagnostic
     * **Feature: codenarc-lint-fixes, Property 13: Multiple Diagnostics Handling**
     * **Validates: Requirements 6.4**
     *
     * For any list of diagnostics, each returned CodeAction should
     * reference exactly one of the input diagnostics.
     */
    @Property(tries = 100)
    fun `property - each action references an input diagnostic`(
        @ForAll("diagnosticLists") diagnosticList: DiagnosticList,
    ): Boolean {
        val (diagnostics, content, _) = diagnosticList

        val actions = lintFixAction.createLintFixActions(testUri, diagnostics, content)

        // Each action should reference exactly one diagnostic from the input
        return actions.all { action ->
            val actionDiagnostics = action.diagnostics ?: emptyList()
            actionDiagnostics.size == 1 && diagnostics.contains(actionDiagnostics.first())
        }
    }

    /**
     * Property test: No Duplicate Actions for Same Diagnostic
     * **Feature: codenarc-lint-fixes, Property 13: Multiple Diagnostics Handling**
     * **Validates: Requirements 6.4**
     *
     * For any list of diagnostics, the system should not produce
     * duplicate actions for the same diagnostic.
     */
    @Property(tries = 100)
    fun `property - no duplicate actions for same diagnostic`(
        @ForAll("diagnosticLists") diagnosticList: DiagnosticList,
    ): Boolean {
        val (diagnostics, content, _) = diagnosticList

        val actions = lintFixAction.createLintFixActions(testUri, diagnostics, content)

        // Extract the diagnostic from each action and check for uniqueness
        val referencedDiagnostics = actions.mapNotNull { action ->
            action.diagnostics?.firstOrNull()
        }

        return referencedDiagnostics.size == referencedDiagnostics.toSet().size
    }

    /**
     * Property test: Non-CodeNarc Diagnostics Don't Produce Actions
     * **Feature: codenarc-lint-fixes, Property 13: Multiple Diagnostics Handling**
     * **Validates: Requirements 6.4**
     *
     * For any list containing non-CodeNarc diagnostics, those diagnostics
     * should not produce any actions.
     */
    @Property(tries = 100)
    fun `property - non-CodeNarc diagnostics produce no actions`(
        @ForAll("mixedDiagnosticLists") diagnosticList: DiagnosticList,
    ): Boolean {
        val (diagnostics, content, _) = diagnosticList

        val actions = lintFixAction.createLintFixActions(testUri, diagnostics, content)

        // All actions should reference CodeNarc diagnostics only
        return actions.all { action ->
            val actionDiagnostic = action.diagnostics?.firstOrNull()
            actionDiagnostic?.source?.equals("CodeNarc", ignoreCase = true) == true
        }
    }

    @Provide
    fun diagnosticLists(): Arbitrary<DiagnosticList> {
        // Generate lists of 1-5 diagnostics with registered handlers
        return Arbitraries.integers().between(1, 5).flatMap { count ->
            generateDiagnosticList(count)
        }
    }

    @Provide
    fun mixedDiagnosticLists(): Arbitrary<DiagnosticList> {
        // Generate lists with a mix of CodeNarc and non-CodeNarc diagnostics
        return Arbitraries.integers().between(2, 6).flatMap { count ->
            generateMixedDiagnosticList(count)
        }
    }

    /**
     * Generates a list of diagnostics with appropriate content.
     */
    private fun generateDiagnosticList(count: Int): Arbitrary<DiagnosticList> {
        val registeredRules = FixHandlerRegistry.getRegisteredRules().toList()

        return Arbitraries.integers().between(0, registeredRules.size - 1)
            .list().ofSize(count)
            .map { indices ->
                val selectedRules = indices.map { registeredRules[it % registeredRules.size] }
                val (content, diagnostics) = generateContentAndDiagnostics(selectedRules)
                DiagnosticList(diagnostics, content, diagnostics.size)
            }
    }

    /**
     * Generates a mixed list with both CodeNarc and non-CodeNarc diagnostics.
     */
    private fun generateMixedDiagnosticList(count: Int): Arbitrary<DiagnosticList> {
        val registeredRules = FixHandlerRegistry.getRegisteredRules().toList()

        return Arbitraries.integers().between(0, registeredRules.size - 1)
            .list().ofSize(count)
            .flatMap { indices ->
                Arbitraries.integers().between(1, count - 1).map { nonCodeNarcCount ->
                    val codeNarcCount = count - nonCodeNarcCount
                    val selectedRules = indices.take(codeNarcCount).map { registeredRules[it % registeredRules.size] }
                    val (content, codeNarcDiagnostics) = generateContentAndDiagnostics(selectedRules)

                    // Add non-CodeNarc diagnostics
                    val nonCodeNarcDiagnostics = (0 until nonCodeNarcCount).map { i ->
                        createNonCodeNarcDiagnostic(i)
                    }

                    val allDiagnostics = codeNarcDiagnostics + nonCodeNarcDiagnostics
                    DiagnosticList(allDiagnostics, content, codeNarcDiagnostics.size)
                }
            }
    }

    /**
     * Generates content and diagnostics for a list of rules.
     * Each rule gets its own line in the content.
     */
    private fun generateContentAndDiagnostics(rules: List<String>): Pair<String, List<Diagnostic>> {
        val contentLines = mutableListOf<String>()
        val diagnostics = mutableListOf<Diagnostic>()

        rules.forEachIndexed { index, ruleName ->
            val (line, startChar, endChar) = getLineForRule(ruleName)
            contentLines.add(line)
            diagnostics.add(createDiagnostic(ruleName, index, startChar, endChar))
        }

        return contentLines.joinToString("\n") to diagnostics
    }

    /**
     * Returns appropriate line content and character positions for each rule.
     */
    private fun getLineForRule(ruleName: String): Triple<String, Int, Int> = when (ruleName) {
        "TrailingWhitespace" -> Triple("def x = 1   ", 0, 12)
        "UnnecessarySemicolon" -> Triple("def x = 1;", 0, 10)
        "ConsecutiveBlankLines" -> Triple("", 0, 0) // Blank line
        "BlankLineBeforePackage" -> Triple("package com.example", 0, 19)
        "UnusedImport", "DuplicateImport", "UnnecessaryGroovyImport", "ImportFromSamePackage" ->
            Triple("import java.util.List", 0, 21)
        "UnnecessaryPublicModifier" -> Triple("public class Test {}", 0, 7)
        "UnnecessaryDefInVariableDeclaration" -> Triple("def String x = 'hello'", 0, 4)
        "UnnecessaryGetter" -> Triple("obj.getName()", 4, 13)
        "UnnecessarySetter" -> Triple("obj.setName(value)", 4, 18)
        "UnnecessaryDotClass" -> Triple("def c = String.class", 8, 20)
        else -> Triple("def x = 1", 0, 9)
    }

    private fun createDiagnostic(ruleName: String, line: Int, startChar: Int, endChar: Int): Diagnostic =
        Diagnostic().apply {
            range = Range(Position(line, startChar), Position(line, endChar))
            message = "CodeNarc: $ruleName violation"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(ruleName)
            severity = DiagnosticSeverity.Warning
        }

    private fun createNonCodeNarcDiagnostic(index: Int): Diagnostic = Diagnostic().apply {
        range = Range(Position(0, 0), Position(0, 5))
        message = "Non-CodeNarc issue $index"
        source = "OtherLinter"
        code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("SomeRule$index")
        severity = DiagnosticSeverity.Warning
    }

    /**
     * Data class to hold diagnostics, content, and expected max actions.
     */
    data class DiagnosticList(val diagnostics: List<Diagnostic>, val content: String, val expectedMaxActions: Int)
}
