package com.github.albertocavalcante.diagnostics.codenarc

import org.codenarc.rule.Violation

/**
 * Calculates precise LSP ranges for CodeNarc violations by using the violation's
 * sourceLine and message to find exact column positions.
 *
 * This is inspired by npm-groovy-lint's approach of defining rule-specific
 * variable extractors and range calculators.
 */
object RuleRangeCalculator {

    /**
     * Calculate the range (start and end columns) for a violation.
     *
     * @param violation The CodeNarc violation
     * @param fallbackLine The source line from the file (used if violation.sourceLine is null)
     * @return Pair of (startColumn, endColumn) in 0-based LSP coordinates
     */
    fun calculateRange(violation: Violation, fallbackLine: String): Pair<Int, Int> {
        val sourceLine = violation.sourceLine ?: fallbackLine
        val ruleName = violation.rule.name
        val message = violation.message ?: ""

        return when (ruleName) {
            "UnnecessarySemicolon" -> calculateUnnecessarySemicolonRange(sourceLine)
            "TrailingWhitespace" -> calculateTrailingWhitespaceRange(sourceLine)
            "UnusedVariable" -> calculateUnusedVariableRange(sourceLine, message)
            "UnusedPrivateField" -> calculateUnusedFieldRange(sourceLine, message)
            "UnusedPrivateMethod" -> calculateUnusedMethodRange(sourceLine, message)
            "UnusedImport" -> calculateFullLineRange(sourceLine)
            "DuplicateImport" -> calculateFullLineRange(sourceLine)
            "UnnecessaryGroovyImport" -> calculateFullLineRange(sourceLine)
            "UnnecessaryPublicModifier" -> calculatePublicModifierRange(sourceLine)
            "UnnecessaryDefInVariableDeclaration" -> calculateDefRange(sourceLine)
            "UnnecessaryDefInMethodDeclaration" -> calculateDefRange(sourceLine)
            "UnnecessaryDefInFieldDeclaration" -> calculateDefRange(sourceLine)
            "UnnecessaryGString" -> calculateGStringRange(sourceLine, message)
            "UnnecessaryDotClass" -> calculateDotClassRange(sourceLine)
            else -> calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Find the last semicolon in the line and highlight just that character.
     */
    private fun calculateUnnecessarySemicolonRange(sourceLine: String): Pair<Int, Int> {
        val semicolonIndex = sourceLine.lastIndexOf(';')
        return if (semicolonIndex >= 0) {
            Pair(semicolonIndex, semicolonIndex + 1)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Find trailing whitespace and highlight from its start to end of line.
     */
    private fun calculateTrailingWhitespaceRange(sourceLine: String): Pair<Int, Int> {
        val trimmedLength = sourceLine.trimEnd().length
        return if (trimmedLength < sourceLine.length) {
            Pair(trimmedLength, sourceLine.length)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Extract variable name from message like "The variable [varName] in class X is not used"
     * and find it in the source line.
     */
    private fun calculateUnusedVariableRange(sourceLine: String, message: String): Pair<Int, Int> {
        val varName = extractBracketedValue(message, "variable")
        return if (varName != null) {
            findIdentifierInLine(sourceLine, varName)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Extract field name from message like "The field [fieldName] is not used"
     */
    private fun calculateUnusedFieldRange(sourceLine: String, message: String): Pair<Int, Int> {
        val fieldName = extractBracketedValue(message, "field")
        return if (fieldName != null) {
            findIdentifierInLine(sourceLine, fieldName)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Extract method name from message like "The method [methodName] is not used"
     */
    private fun calculateUnusedMethodRange(sourceLine: String, message: String): Pair<Int, Int> {
        val methodName = extractBracketedValue(message, "method")
        return if (methodName != null) {
            findIdentifierInLine(sourceLine, methodName)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Highlight the entire line (after leading whitespace).
     */
    private fun calculateFullLineRange(sourceLine: String): Pair<Int, Int> {
        val startIndex = sourceLine.indexOfFirst { !it.isWhitespace() }
        return if (startIndex >= 0) {
            Pair(startIndex, sourceLine.length)
        } else {
            Pair(0, sourceLine.length)
        }
    }

    /**
     * Find "public" keyword in the line.
     */
    private fun calculatePublicModifierRange(sourceLine: String): Pair<Int, Int> =
        findKeywordInLine(sourceLine, "public")

    /**
     * Find "def" keyword in the line.
     */
    private fun calculateDefRange(sourceLine: String): Pair<Int, Int> = findKeywordInLine(sourceLine, "def")

    /**
     * Find GString (double-quoted string) in the line.
     */
    private fun calculateGStringRange(sourceLine: String, message: String): Pair<Int, Int> {
        // Try to find the string content from the message
        val stringContent = extractQuotedValue(message)
        return if (stringContent != null) {
            val index = sourceLine.indexOf("\"$stringContent\"")
            if (index >= 0) {
                Pair(index, index + stringContent.length + 2) // +2 for quotes
            } else {
                calculateDefaultRange(sourceLine)
            }
        } else {
            // Find first double-quoted string
            val startQuote = sourceLine.indexOf('"')
            if (startQuote >= 0) {
                val endQuote = sourceLine.indexOf('"', startQuote + 1)
                if (endQuote > startQuote) {
                    Pair(startQuote, endQuote + 1)
                } else {
                    calculateDefaultRange(sourceLine)
                }
            } else {
                calculateDefaultRange(sourceLine)
            }
        }
    }

    /**
     * Find ".class" in the line.
     */
    private fun calculateDotClassRange(sourceLine: String): Pair<Int, Int> {
        val index = sourceLine.indexOf(".class")
        return if (index >= 0) {
            Pair(index, index + 6) // ".class" is 6 characters
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Default range: find first word or highlight from first non-whitespace.
     */
    private fun calculateDefaultRange(sourceLine: String): Pair<Int, Int> {
        val startIndex = sourceLine.indexOfFirst { !it.isWhitespace() }
        if (startIndex < 0) {
            return Pair(0, maxOf(1, sourceLine.length))
        }

        // Find end of first word
        val remaining = sourceLine.substring(startIndex)
        val wordEnd = remaining.indexOfFirst { it.isWhitespace() || it in "(){}[].,;:" }
        return if (wordEnd > 0) {
            Pair(startIndex, startIndex + wordEnd)
        } else {
            Pair(startIndex, sourceLine.length)
        }
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    /**
     * Extract value in brackets after a keyword, e.g., "The variable [varName]" -> "varName"
     */
    private fun extractBracketedValue(message: String, keyword: String): String? {
        // Pattern: "The <keyword> [<value>]"
        val regex = Regex("""The $keyword \[([^\]]+)\]""", RegexOption.IGNORE_CASE)
        return regex.find(message)?.groupValues?.getOrNull(1)
    }

    /**
     * Extract a quoted value from a message.
     */
    private fun extractQuotedValue(message: String): String? {
        val regex = Regex(""""([^"]+)"""")
        return regex.find(message)?.groupValues?.getOrNull(1)
    }

    /**
     * Find an identifier in the source line and return its range.
     */
    private fun findIdentifierInLine(sourceLine: String, identifier: String): Pair<Int, Int> {
        // Use word boundary matching to find the exact identifier
        val regex = Regex("""\b${Regex.escape(identifier)}\b""")
        val match = regex.find(sourceLine)
        return if (match != null) {
            Pair(match.range.first, match.range.last + 1) // +1 for exclusive end
        } else {
            // Fallback: simple indexOf
            val index = sourceLine.indexOf(identifier)
            if (index >= 0) {
                Pair(index, index + identifier.length)
            } else {
                calculateDefaultRange(sourceLine)
            }
        }
    }

    /**
     * Find a keyword in the source line and return its range.
     */
    private fun findKeywordInLine(sourceLine: String, keyword: String): Pair<Int, Int> {
        val regex = Regex("""\b$keyword\b""")
        val match = regex.find(sourceLine)
        return if (match != null) {
            Pair(match.range.first, match.range.last + 1)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }
}
