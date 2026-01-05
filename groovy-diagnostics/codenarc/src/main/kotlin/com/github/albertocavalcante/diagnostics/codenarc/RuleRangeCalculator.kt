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
            // Unnecessary code rules
            "UnnecessarySemicolon" -> calculateUnnecessarySemicolonRange(sourceLine)
            "UnnecessaryPublicModifier" -> calculatePublicModifierRange(sourceLine)
            "UnnecessaryDefInVariableDeclaration" -> calculateDefRange(sourceLine)
            "UnnecessaryDefInMethodDeclaration" -> calculateDefRange(sourceLine)
            "UnnecessaryDefInFieldDeclaration" -> calculateDefRange(sourceLine)
            "UnnecessaryGString" -> calculateGStringRange(sourceLine, message)
            "UnnecessaryDotClass" -> calculateDotClassRange(sourceLine)

            // Unused code rules
            "UnusedVariable" -> calculateUnusedVariableRange(sourceLine, message)
            "UnusedPrivateField" -> calculateUnusedFieldRange(sourceLine, message)
            "UnusedPrivateMethod" -> calculateUnusedMethodRange(sourceLine, message)
            "UnusedImport" -> calculateFullLineRange(sourceLine)

            // Import rules
            "DuplicateImport" -> calculateFullLineRange(sourceLine)
            "UnnecessaryGroovyImport" -> calculateFullLineRange(sourceLine)

            // Formatting rules
            "TrailingWhitespace" -> calculateTrailingWhitespaceRange(sourceLine)
            "Indentation" -> calculateIndentationRange(sourceLine)
            "SpaceAfterComma" -> calculateCommaRange(sourceLine)
            "SpaceBeforeOpeningBrace" -> calculateOpeningBraceRange(sourceLine)
            "SpaceAfterOpeningBrace" -> calculateOpeningBraceRange(sourceLine)
            "SpaceBeforeClosingBrace" -> calculateClosingBraceRange(sourceLine)
            "SpaceAfterClosingBrace" -> calculateClosingBraceRange(sourceLine)
            "SpaceInsideParentheses" -> calculateOpeningParenthesisRange(sourceLine)
            "SpaceAroundClosureArrow" -> calculateClosureArrowRange(sourceLine)
            "BlockEndsWithBlankLine" -> calculateEmptyLineRange(sourceLine)
            "BlockStartsWithBlankLine" -> calculateEmptyLineRange(sourceLine)
            "ClassEndsWithBlankLine" -> calculateEmptyLineRange(sourceLine)
            "ClassStartsWithBlankLine" -> calculateEmptyLineRange(sourceLine)
            "BracesForClass" -> findKeywordInLine(sourceLine, "class")
            "BracesForMethod" -> findKeywordInLine(sourceLine, "def")
            "BracesForIfElse" -> findKeywordInLine(sourceLine, "if")
            "BracesForLoop" -> calculateLoopKeywordRange(sourceLine)
            "BracesForTryCatchFinally" -> calculateTryCatchFinallyKeywordRange(sourceLine)
            "ClosureStatementOnOpeningLineOfMultipleLineClosure" -> calculateClosureArrowRange(sourceLine)
            "LineLength" -> calculateFullLineRange(sourceLine)
            "MissingBlankLineAfterImports" -> calculateDefaultRange(sourceLine)
            "MissingBlankLineAfterPackage" -> calculateDefaultRange(sourceLine)

            // Naming convention rules
            "ClassName" -> calculateNameViolationRange(sourceLine, message, "class")
            "MethodName" -> calculateNameViolationRange(sourceLine, message, "method")
            "VariableName" -> calculateNameViolationRange(sourceLine, message, "variable")
            "FieldName" -> calculateNameViolationRange(sourceLine, message, "field")
            "ParameterName" -> calculateNameViolationRange(sourceLine, message, "parameter")
            "PropertyName" -> calculateNameViolationRange(sourceLine, message, "property")
            "PackageName" -> calculatePackageNameRange(sourceLine, message)

            // Basic rules
            "EmptyClass" -> findKeywordInLine(sourceLine, "class")
            "EmptyMethod" -> findKeywordInLine(sourceLine, "def")
            "EmptyIfStatement" -> findKeywordInLine(sourceLine, "if")
            "EmptyElseBlock" -> findKeywordInLine(sourceLine, "else")
            "EmptyTryBlock" -> findKeywordInLine(sourceLine, "try")
            "EmptyCatchBlock" -> findKeywordInLine(sourceLine, "catch")
            "EmptyFinallyBlock" -> findKeywordInLine(sourceLine, "finally")
            "EmptyForStatement" -> findKeywordInLine(sourceLine, "for")
            "EmptyWhileStatement" -> findKeywordInLine(sourceLine, "while")
            "EmptySwitchStatement" -> findKeywordInLine(sourceLine, "switch")
            "EmptySynchronizedStatement" -> findKeywordInLine(sourceLine, "synchronized")

            // Groovyism rules
            "GStringExpressionWithinString" -> calculateGStringRange(sourceLine, message)
            "ExplicitCallToEqualsMethod" -> calculateMethodCallRange(sourceLine, "equals")
            "ExplicitCallToCompareToMethod" -> calculateMethodCallRange(sourceLine, "compareTo")
            "GetterMethodCouldBeProperty" -> calculateGetterNameRange(sourceLine, message)

            // Exception rules
            "CatchException" -> calculateExceptionTypeRange(sourceLine, "Exception")
            "CatchThrowable" -> calculateExceptionTypeRange(sourceLine, "Throwable")
            "ThrowException" -> findKeywordInLine(sourceLine, "throw")
            "ThrowRuntimeException" -> findKeywordInLine(sourceLine, "throw")
            "CatchNullPointerException" -> calculateExceptionTypeRange(sourceLine, "NullPointerException")

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
        if (stringContent != null) {
            val index = sourceLine.indexOf("\"$stringContent\"")
            if (index >= 0) {
                return Pair(index, index + stringContent.length + 2) // +2 for quotes
            }
        }

        // Find first semi-smart unescaped double-quoted string
        val startQuote = sourceLine.indexOf('"')
        if (startQuote >= 0) {
            var i = startQuote + 1
            while (i < sourceLine.length) {
                // If we find a quote that isn't escaped
                if (sourceLine[i] == '"' && sourceLine.getOrNull(i - 1) != '\\') {
                    return Pair(startQuote, i + 1)
                }
                i++
            }
        }

        return calculateDefaultRange(sourceLine)
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
            // Empty line or whitespace only -> highlighted as (0, 0)
            return Pair(0, 0)
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

    // ==========================================
    // FORMATTING RULES HELPERS
    // ==========================================

    /**
     * Calculate range for indentation violations.
     * Highlights from start of line to end of whitespace.
     */
    private fun calculateIndentationRange(sourceLine: String): Pair<Int, Int> {
        val firstNonWhitespace = sourceLine.indexOfFirst { !it.isWhitespace() }
        return if (firstNonWhitespace > 0) {
            Pair(0, firstNonWhitespace)
        } else if (firstNonWhitespace == 0) {
            // No indentation at all
            Pair(0, 0)
        } else {
            // Line is all whitespace
            Pair(0, sourceLine.length)
        }
    }

    /**
     * Calculate range for comma violations (missing space after comma).
     * Finds the first comma without a space after it.
     */
    private fun calculateCommaRange(sourceLine: String): Pair<Int, Int> {
        // Find first comma not followed by a space or end of line
        var index = 0
        while (index < sourceLine.length) {
            if (sourceLine[index] == ',') {
                val nextChar = sourceLine.getOrNull(index + 1)
                if (nextChar != null && nextChar != ' ' && nextChar != '\t') {
                    // Found comma without space
                    return Pair(index, index + 1)
                }
            }
            index++
        }
        // Fallback: find any comma
        val commaIndex = sourceLine.indexOf(',')
        return if (commaIndex >= 0) {
            Pair(commaIndex, commaIndex + 1)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Calculate range for opening brace violations.
     */
    private fun calculateOpeningBraceRange(sourceLine: String): Pair<Int, Int> {
        val braceIndex = sourceLine.indexOf('{')
        return if (braceIndex >= 0) {
            Pair(braceIndex, braceIndex + 1)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Calculate range for closing brace violations.
     */
    private fun calculateClosingBraceRange(sourceLine: String): Pair<Int, Int> {
        val braceIndex = sourceLine.indexOf('}')
        return if (braceIndex >= 0) {
            Pair(braceIndex, braceIndex + 1)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Calculate range for opening parenthesis violations.
     */
    private fun calculateOpeningParenthesisRange(sourceLine: String): Pair<Int, Int> {
        val parenIndex = sourceLine.indexOf('(')
        return if (parenIndex >= 0) {
            // Check if there's space after the parenthesis
            if (parenIndex + 1 < sourceLine.length && sourceLine[parenIndex + 1] == ' ') {
                Pair(parenIndex, parenIndex + 2) // Include the space
            } else {
                Pair(parenIndex, parenIndex + 1)
            }
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Calculate range for closure arrow violations (->).
     */
    private fun calculateClosureArrowRange(sourceLine: String): Pair<Int, Int> {
        val arrowIndex = sourceLine.indexOf("->")
        return if (arrowIndex >= 0) {
            Pair(arrowIndex, arrowIndex + 2)
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Calculate range for empty line violations.
     * Returns (0, 0) for empty lines.
     */
    private fun calculateEmptyLineRange(sourceLine: String): Pair<Int, Int> = if (sourceLine.isBlank()) {
        Pair(0, 0)
    } else {
        calculateDefaultRange(sourceLine)
    }

    /**
     * Calculate range for loop keyword violations (for, while).
     */
    private fun calculateLoopKeywordRange(sourceLine: String): Pair<Int, Int> = findKeywordInLine(sourceLine, "for")
        .takeIf { it != calculateDefaultRange(sourceLine) }
        ?: findKeywordInLine(sourceLine, "while")

    /**
     * Calculate range for try/catch/finally keyword violations.
     */
    private fun calculateTryCatchFinallyKeywordRange(sourceLine: String): Pair<Int, Int> =
        findKeywordInLine(sourceLine, "try")
            .takeIf { it != calculateDefaultRange(sourceLine) }
            ?: findKeywordInLine(sourceLine, "catch")
                .takeIf { it != calculateDefaultRange(sourceLine) }
            ?: findKeywordInLine(sourceLine, "finally")

    // ==========================================
    // NAMING CONVENTION RULES HELPERS
    // ==========================================

    /**
     * Calculate range for naming convention violations.
     * Extracts the name from the message and finds it in the source line.
     */
    private fun calculateNameViolationRange(sourceLine: String, message: String, type: String): Pair<Int, Int> {
        // Extract name from message like "The class name [myClass] does not match..."
        val name = extractBracketedValue(message, "$type name")
            ?: extractBracketedValue(message, type)

        if (name != null) {
            // Find the name in the source line
            return findIdentifierInLine(sourceLine, name)
        }

        // Fallback: find the identifier after the type keyword
        return when (type) {
            "class" -> findIdentifierAfterKeyword(sourceLine, "class")
            "method" -> findIdentifierAfterKeyword(sourceLine, "def")
            "variable" -> findIdentifierAfterKeyword(sourceLine, "def")
            "field", "property" -> findLastIdentifier(sourceLine)
            "parameter" -> findLastIdentifier(sourceLine)
            else -> calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Find identifier after a keyword.
     */
    private fun findIdentifierAfterKeyword(sourceLine: String, keyword: String): Pair<Int, Int> {
        val keywordIndex = sourceLine.indexOf(keyword)
        if (keywordIndex >= 0) {
            val afterKeyword = sourceLine.substring(keywordIndex + keyword.length).trimStart()
            val identifierMatch = Regex("""^[a-zA-Z_]\w*""").find(afterKeyword)
            if (identifierMatch != null) {
                val startIndex = sourceLine.indexOf(afterKeyword, keywordIndex) + identifierMatch.range.first
                return Pair(startIndex, startIndex + identifierMatch.value.length)
            }
        }
        return calculateDefaultRange(sourceLine)
    }

    /**
     * Find the last identifier in the line (for field/property names).
     */
    private fun findLastIdentifier(sourceLine: String): Pair<Int, Int> {
        val identifierMatches = Regex("""\b[a-zA-Z_]\w*\b""").findAll(sourceLine).toList()
        if (identifierMatches.isNotEmpty()) {
            val lastMatch = identifierMatches.last()
            return Pair(lastMatch.range.first, lastMatch.range.last + 1)
        }
        return calculateDefaultRange(sourceLine)
    }

    /**
     * Calculate range for package name violations.
     */
    private fun calculatePackageNameRange(sourceLine: String, message: String): Pair<Int, Int> {
        // Extract package name from message
        val packageName = extractBracketedValue(message, "package name")

        if (packageName != null) {
            val index = sourceLine.indexOf(packageName)
            if (index >= 0) {
                return Pair(index, index + packageName.length)
            }
        }

        // Fallback: highlight from "package" to end of line
        val packageIndex = sourceLine.indexOf("package")
        return if (packageIndex >= 0) {
            val afterPackage = packageIndex + "package".length
            val trimmedStart = sourceLine.substring(afterPackage).indexOfFirst { !it.isWhitespace() }
            if (trimmedStart >= 0) {
                val start = afterPackage + trimmedStart
                Pair(start, sourceLine.length)
            } else {
                calculateDefaultRange(sourceLine)
            }
        } else {
            calculateDefaultRange(sourceLine)
        }
    }

    // ==========================================
    // GROOVYISM RULES HELPERS
    // ==========================================

    /**
     * Calculate range for method call violations (e.g., .equals(), .compareTo()).
     */
    private fun calculateMethodCallRange(sourceLine: String, methodName: String): Pair<Int, Int> {
        val dotMethod = ".$methodName"
        val index = sourceLine.indexOf(dotMethod)
        return if (index >= 0) {
            Pair(index, index + dotMethod.length)
        } else {
            // Try without dot
            return findIdentifierInLine(sourceLine, methodName)
        }
    }

    /**
     * Calculate range for getter method violations.
     */
    private fun calculateGetterNameRange(sourceLine: String, message: String): Pair<Int, Int> {
        // Extract method name from message like "The method [getName] could be a property"
        val methodName = extractBracketedValue(message, "method")

        if (methodName != null) {
            return findIdentifierInLine(sourceLine, methodName)
        }

        // Fallback: find identifier after "def"
        return findIdentifierAfterKeyword(sourceLine, "def")
    }

    // ==========================================
    // EXCEPTION RULES HELPERS
    // ==========================================

    /**
     * Calculate range for exception type violations (e.g., catch (Exception e)).
     */
    private fun calculateExceptionTypeRange(sourceLine: String, exceptionType: String): Pair<Int, Int> =
        findIdentifierInLine(sourceLine, exceptionType)
}
