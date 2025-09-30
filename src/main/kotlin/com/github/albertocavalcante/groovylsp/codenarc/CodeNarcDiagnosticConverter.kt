package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.dsl.RangeBuilder
import com.github.albertocavalcante.groovylsp.dsl.diagnostic
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * Converts CodeNarc violations to LSP diagnostics.
 *
 * This converter maps CodeNarc's rule violations to LSP diagnostic format,
 * handling severity mapping, position calculation, and message formatting.
 */
object CodeNarcDiagnosticConverter {

    /**
     * Converts a single CodeNarc violation to an LSP diagnostic.
     *
     * @param violation The CodeNarc violation to convert
     * @param sourceLines Optional array of source lines for better position calculation
     * @return LSP diagnostic representing the violation
     */
    fun convertViolation(violation: Violation, sourceLines: List<String>? = null): Diagnostic = diagnostic {
        // Convert position from CodeNarc to LSP format
        val position = calculatePosition(violation, sourceLines)
        range(position)

        // Map severity based on rule priority
        val severity = mapSeverity(violation.rule)
        when (severity) {
            DiagnosticSeverity.Error -> error(formatMessage(violation))
            DiagnosticSeverity.Warning -> warning(formatMessage(violation))
            DiagnosticSeverity.Information -> info(formatMessage(violation))
            DiagnosticSeverity.Hint -> hint(formatMessage(violation))
        }

        // Set diagnostic metadata
        source("codenarc")
        code(violation.rule.name)
    }

    /**
     * Converts a list of CodeNarc violations to LSP diagnostics.
     *
     * @param violations List of CodeNarc violations
     * @param sourceLines Optional array of source lines for better position calculation
     * @return List of LSP diagnostics
     */
    fun convertViolations(violations: List<Violation>, sourceLines: List<String>? = null): List<Diagnostic> =
        violations.map { violation ->
            convertViolation(violation, sourceLines)
        }

    /**
     * Calculates the position range for a violation in LSP format.
     *
     * CodeNarc provides line numbers (1-based) but limited column information.
     * We try to provide the best possible range based on available data.
     */
    private fun calculatePosition(violation: Violation, sourceLines: List<String>?): org.eclipse.lsp4j.Range {
        // CodeNarc uses 1-based line numbers, LSP uses 0-based
        val line = maxOf(0, (violation.lineNumber ?: 1) - 1)

        // Try to calculate column position based on the source line
        if (sourceLines != null && line < sourceLines.size) {
            val sourceLine = sourceLines[line]
            val range = calculateColumnRange(sourceLine, violation)
            return RangeBuilder.range(line, range.first, range.second)
        }

        // Fallback: highlight the entire line
        return RangeBuilder.line(line)
    }

    /**
     * Attempts to calculate column range for a violation within a source line.
     *
     * This is a best-effort approach since CodeNarc doesn't always provide
     * precise column information.
     */
    private fun calculateColumnRange(sourceLine: String, violation: Violation): Pair<Int, Int> {
        // If we have a source line in the violation, try to find it
        violation.sourceLine?.let { violationLine ->
            val trimmedViolationLine = violationLine.trim()
            if (trimmedViolationLine.isNotEmpty()) {
                val index = sourceLine.indexOf(trimmedViolationLine)
                if (index >= 0) {
                    return Pair(index, index + trimmedViolationLine.length)
                }
            }
        }

        // For specific rule types, try to provide better positioning
        val position = when (violation.rule.name) {
            "UnusedImport" -> {
                // Look for import statement
                val importMatch = Regex("""import\s+[\w.]+""").find(sourceLine)
                importMatch?.let { Pair(it.range.first, it.range.last + 1) }
            }

            "TrailingWhitespace" -> {
                // Highlight trailing whitespace
                val trimmedLength = sourceLine.trimEnd().length
                if (trimmedLength < sourceLine.length) {
                    Pair(trimmedLength, sourceLine.length)
                } else {
                    null
                }
            }

            "ConsecutiveBlankLines" -> {
                // Highlight the entire line for blank line issues
                Pair(0, sourceLine.length)
            }

            "UnnecessarySemicolon" -> {
                // Look for semicolon
                val semicolonIndex = sourceLine.lastIndexOf(';')
                if (semicolonIndex >= 0) {
                    Pair(semicolonIndex, semicolonIndex + 1)
                } else {
                    null
                }
            }

            else -> null
        }

        if (position != null) {
            return position
        }

        // Default: highlight the entire line content (excluding leading whitespace)
        val startCol = sourceLine.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
        val endCol = sourceLine.length
        return Pair(startCol, endCol)
    }

    /**
     * Maps CodeNarc rule priority to LSP diagnostic severity.
     *
     * CodeNarc uses priority levels 1-3 where:
     * - 1 = Highest priority (most severe)
     * - 2 = Medium priority
     * - 3 = Lowest priority (least severe)
     */
    private fun mapSeverity(rule: Rule): DiagnosticSeverity = when (rule.priority) {
        PRIORITY_HIGH -> DiagnosticSeverity.Error
        PRIORITY_MEDIUM -> DiagnosticSeverity.Warning
        PRIORITY_LOW -> DiagnosticSeverity.Information
        else -> DiagnosticSeverity.Warning // Default fallback
    }

    /**
     * Formats the diagnostic message from a CodeNarc violation.
     *
     * Combines the violation message with the rule name to provide
     * clear, actionable feedback to the user.
     */
    private fun formatMessage(violation: Violation): String {
        val baseMessage = violation.message?.takeIf { it.isNotEmpty() }
            ?: "Violation of rule '${violation.rule.name}'"

        // For now, just return the base message
        // TODO: Add rule description when we understand CodeNarc Rule API better

        return baseMessage
    }
}

private const val PRIORITY_HIGH = 1
private const val PRIORITY_MEDIUM = 2
private const val PRIORITY_LOW = 3
