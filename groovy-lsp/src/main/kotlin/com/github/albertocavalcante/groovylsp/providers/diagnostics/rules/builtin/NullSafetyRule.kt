package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.AbstractDiagnosticRule
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Detects potentially unsafe null handling patterns in Groovy code.
 *
 * This rule identifies:
 * - Direct field/method access on potentially nullable expressions
 * - Missing null-safe operators where they might be needed
 *
 * NOTE: This is a heuristic-based rule that may have false positives.
 * It's designed to encourage defensive programming practices.
 */
class NullSafetyRule : AbstractDiagnosticRule() {

    override val id = "groovy-null-safety"

    override val description = "Detect potentially unsafe null handling in Groovy code"

    override val defaultSeverity = DiagnosticSeverity.Hint

    override val enabledByDefault = false // Disabled by default as it can be noisy

    override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val lines = content.lines()

        // Pattern: method calls or field access that might be on null values
        // Look for cases where variables are accessed without null-safe operators
        val unsafeAccessPattern = Regex("""(\w+)\.(get|find|findAll|collect)\(.*?\)\.\w+""")

        lines.forEachIndexed { lineIndex, line ->
            // Skip if already using null-safe operator
            if (line.contains("?.")) {
                return@forEachIndexed
            }

            var searchFrom = 0
            while (true) {
                val match = unsafeAccessPattern.find(line, searchFrom) ?: break

                // Check if this is in a comment or string
                val beforeMatch = line.substring(0, match.range.first)
                if (!beforeMatch.contains("//") && !isInString(line, match.range.first)) {
                    diagnostics.add(
                        diagnostic(
                            lineIndex,
                            match.range.first,
                            match.range.last + 1,
                            "Consider using null-safe operator (?.) to prevent potential NPE",
                            defaultSeverity,
                        ),
                    )
                }

                searchFrom = match.range.last + 1
            }
        }

        return diagnostics
    }

    private fun isInString(line: String, position: Int): Boolean {
        var inString = false
        var stringChar: Char? = null

        for (i in 0 until position) {
            val char = line[i]
            if (char == '"' || char == '\'') {
                if (!inString) {
                    inString = true
                    stringChar = char
                } else if (char == stringChar) {
                    // Check if escaped
                    if (i == 0 || line[i - 1] != '\\') {
                        inString = false
                        stringChar = null
                    }
                }
            }
        }

        return inString
    }
}
