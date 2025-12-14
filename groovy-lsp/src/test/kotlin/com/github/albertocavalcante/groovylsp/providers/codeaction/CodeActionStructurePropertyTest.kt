@file:Suppress("ktlint:standard:function-naming")

package com.github.albertocavalcante.groovylsp.providers.codeaction

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Property-based tests for CodeAction structure validation.
 *
 * **Feature: codenarc-lint-fixes, Property 12: Code Action Structure Validity**
 * **Validates: Requirements 6.1, 6.2, 6.3**
 */
class CodeActionStructurePropertyTest {

    private val lintFixAction = LintFixAction()
    private val testUri = "file:///test.groovy"

    /**
     * Property test: Code Action Structure Validity
     * **Feature: codenarc-lint-fixes, Property 12: Code Action Structure Validity**
     * **Validates: Requirements 6.1, 6.2, 6.3**
     *
     * For any successfully created CodeAction, it should have:
     * (a) kind equal to CodeActionKind.QuickFix
     * (b) a non-empty title
     * (c) the original diagnostic in its diagnostics list
     * (d) a non-null edit with changes
     */
    @Property(tries = 100)
    fun `property - code actions have valid structure`(
        @ForAll("registeredRulesWithContent") ruleAndContent: RuleAndContent,
    ): Boolean {
        val (ruleName, content, diagnostic) = ruleAndContent

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        // If no action was created (handler returned null), that's valid - skip validation
        if (actions.isEmpty()) {
            return true
        }

        val action = actions.first()

        // (a) kind equal to CodeActionKind.QuickFix
        val hasQuickFixKind = action.kind == CodeActionKind.QuickFix

        // (b) a non-empty title
        val hasNonEmptyTitle = !action.title.isNullOrBlank()

        // (c) the original diagnostic in its diagnostics list
        val containsOriginalDiagnostic = action.diagnostics?.contains(diagnostic) == true

        // (d) a non-null edit with changes
        val hasNonNullEdit = action.edit != null
        val hasChanges = action.edit?.changes?.isNotEmpty() == true ||
            action.edit?.documentChanges?.isNotEmpty() == true

        return hasQuickFixKind && hasNonEmptyTitle && containsOriginalDiagnostic && hasNonNullEdit && hasChanges
    }

    /**
     * Property test: Code Action Title Matches Registry
     * **Feature: codenarc-lint-fixes, Property 12: Code Action Structure Validity**
     * **Validates: Requirements 6.3**
     *
     * For any successfully created CodeAction, its title should match
     * the title registered in FixHandlerRegistry for that rule.
     */
    @Property(tries = 100)
    fun `property - code action title matches registry title`(
        @ForAll("registeredRulesWithContent") ruleAndContent: RuleAndContent,
    ): Boolean {
        val (ruleName, content, diagnostic) = ruleAndContent

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        // If no action was created, skip validation
        if (actions.isEmpty()) {
            return true
        }

        val action = actions.first()
        val expectedTitle = FixHandlerRegistry.getTitle(ruleName)

        return action.title == expectedTitle
    }

    /**
     * Property test: Code Action Edit Targets Correct URI
     * **Feature: codenarc-lint-fixes, Property 12: Code Action Structure Validity**
     * **Validates: Requirements 6.1**
     *
     * For any successfully created CodeAction, its edit should target
     * the same URI that was passed to createLintFixActions.
     */
    @Property(tries = 100)
    fun `property - code action edit targets correct URI`(
        @ForAll("registeredRulesWithContent") ruleAndContent: RuleAndContent,
    ): Boolean {
        val (ruleName, content, diagnostic) = ruleAndContent

        val actions = lintFixAction.createLintFixActions(testUri, listOf(diagnostic), content)

        // If no action was created, skip validation
        if (actions.isEmpty()) {
            return true
        }

        val action = actions.first()
        val changes = action.edit?.changes ?: return false

        return changes.containsKey(testUri)
    }

    @Provide
    fun registeredRulesWithContent(): Arbitrary<RuleAndContent> =
        Arbitraries.of(FixHandlerRegistry.getRegisteredRules().toList())
            .flatMap { ruleName ->
                generateContentForRule(ruleName).map { (content, diagnostic) ->
                    RuleAndContent(ruleName, content, diagnostic)
                }
            }

    /**
     * Generates appropriate content and diagnostic for each rule type.
     */
    private fun generateContentForRule(ruleName: String): Arbitrary<Pair<String, Diagnostic>> = when (ruleName) {
        "TrailingWhitespace" -> Arbitraries.just(
            "def x = 1   \n" to createDiagnostic(ruleName, 0, 0, 12),
        )
        "UnnecessarySemicolon" -> Arbitraries.just(
            "def x = 1;\n" to createDiagnostic(ruleName, 0, 0, 10),
        )
        "ConsecutiveBlankLines" -> Arbitraries.just(
            "def x = 1\n\n\ndef y = 2\n" to createDiagnostic(ruleName, 1, 0, 0),
        )
        "BlankLineBeforePackage" -> Arbitraries.just(
            "\n\npackage com.example\n" to createDiagnostic(ruleName, 2, 0, 19),
        )
        "UnusedImport", "DuplicateImport", "UnnecessaryGroovyImport", "ImportFromSamePackage" -> Arbitraries.just(
            "import java.util.List\nclass Test {}\n" to createDiagnostic(ruleName, 0, 0, 21),
        )
        "UnnecessaryPublicModifier" -> Arbitraries.just(
            "public class Test {}\n" to createDiagnostic(ruleName, 0, 0, 7),
        )
        "UnnecessaryDefInVariableDeclaration" -> Arbitraries.just(
            "def String x = 'hello'\n" to createDiagnostic(ruleName, 0, 0, 4),
        )
        "UnnecessaryGetter" -> Arbitraries.just(
            "obj.getName()\n" to createDiagnostic(ruleName, 0, 4, 13),
        )
        "UnnecessarySetter" -> Arbitraries.just(
            "obj.setName(value)\n" to createDiagnostic(ruleName, 0, 4, 18),
        )
        "UnnecessaryDotClass" -> Arbitraries.just(
            "def c = String.class\n" to createDiagnostic(ruleName, 0, 8, 20),
        )
        else -> Arbitraries.just(
            "def x = 1\n" to createDiagnostic(ruleName, 0, 0, 9),
        )
    }

    private fun createDiagnostic(ruleName: String, line: Int, startChar: Int, endChar: Int): Diagnostic =
        Diagnostic().apply {
            range = Range(Position(line, startChar), Position(line, endChar))
            message = "CodeNarc: $ruleName violation"
            source = "CodeNarc"
            code = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(ruleName)
            severity = DiagnosticSeverity.Warning
        }

    /**
     * Data class to hold rule name, content, and diagnostic together.
     */
    data class RuleAndContent(val ruleName: String, val content: String, val diagnostic: Diagnostic)
}
