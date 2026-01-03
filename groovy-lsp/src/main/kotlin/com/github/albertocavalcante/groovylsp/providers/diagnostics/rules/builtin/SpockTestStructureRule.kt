package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Detects incomplete Spock test methods.
 *
 * Spock tests typically follow the given-when-then or expect-where pattern.
 * This rule helps identify test methods that are missing key blocks.
 *
 * NOTE: Simple pattern-based detection. AST analysis would provide
 * more accurate Spock block detection.
 */
class SpockTestStructureRule : AbstractDiagnosticRule() {

    private companion object {
        private const val LOOK_AHEAD_LINES = 20
        private const val RANGE_END_OFFSET = 1
    }

    override val id = "spock-test-structure"

    override val description = "Detect incomplete Spock test methods missing key blocks"

    override val analysisType = DiagnosticAnalysisType.HEURISTIC

    override val defaultSeverity = DiagnosticSeverity.Information

    override val enabledByDefault = true

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        // Only analyze Spock test files
        if (!uri.path.contains("Spec.groovy") && !uri.path.contains("Test.groovy")) {
            return emptyList()
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val lines = content.lines()

        // Find test methods (def "test name"() or def testName())
        val testMethodPattern = Regex("""def\s+["'].*["']\s*\(\s*\)\s*\{|def\s+test\w+\s*\(\s*\)\s*\{""")

        lines.forEachIndexed { lineIndex, line ->
            val match = testMethodPattern.find(line)
            if (match != null) {
                // Check the next several lines for Spock blocks
                val endLine = minOf(lineIndex + LOOK_AHEAD_LINES, lines.size)
                val methodContent = lines.subList(lineIndex, endLine).joinToString("\n")

                val hasGiven = methodContent.contains(Regex("""(\n|^)\s*given:"""))
                val hasWhen = methodContent.contains(Regex("""(\n|^)\s*when:"""))
                val hasThen = methodContent.contains(Regex("""(\n|^)\s*then:"""))
                val hasExpect = methodContent.contains(Regex("""(\n|^)\s*expect:"""))
                val hasWhere = methodContent.contains(Regex("""(\n|^)\s*where:"""))

                // Check for valid patterns
                val hasGivenWhenThen = hasGiven || (hasWhen && hasThen)
                val hasExpectPattern = hasExpect
                val isDataDriven = hasWhere

                if (!hasGivenWhenThen && !hasExpectPattern && !isDataDriven) {
                    diagnostics.add(
                        diagnostic(
                            lineIndex,
                            match.range.first,
                            match.range.last + RANGE_END_OFFSET,
                            "Spock test may be missing expected blocks (given/when/then, expect, or where)",
                            defaultSeverity,
                        ),
                    )
                }
            }
        }

        return diagnostics
    }
}
