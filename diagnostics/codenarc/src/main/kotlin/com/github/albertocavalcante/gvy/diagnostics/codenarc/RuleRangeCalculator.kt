package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codenarc.rule.Violation

/**
 * Calculates precise LSP ranges for CodeNarc violations by using the violation's
 * sourceLine and message to find exact column positions.
 *
 * This is inspired by npm-groovy-lint's approach of defining rule-specific
 * variable extractors and range calculators.
 *
 * TODO(#674): Replace heuristic-based positioning with AST-aware positioning.
 *   Current implementation uses string matching/regex which is brittle and approximate.
 *   We should leverage our parsed AST (GroovyParser) for deterministic positioning.
 *   See: https://github.com/albertocavalcante/gvy/issues/674
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
            "BracesForClass" -> findKeywordOrDefault(sourceLine, "class")
            "BracesForMethod" -> findKeywordOrDefault(sourceLine, "def")
            "BracesForIfElse" -> findKeywordOrDefault(sourceLine, "if")
            "BracesForLoop" -> calculateLoopKeywordRange(sourceLine)
            "BracesForTryCatchFinally" -> calculateTryCatchFinallyKeywordRange(sourceLine, message)
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
            "EmptyClass" -> findKeywordOrDefault(sourceLine, "class")
            "EmptyMethod" -> findKeywordOrDefault(sourceLine, "def")
            "EmptyIfStatement" -> findKeywordOrDefault(sourceLine, "if")
            "EmptyElseBlock" -> findKeywordOrDefault(sourceLine, "else")
            "EmptyTryBlock" -> findKeywordOrDefault(sourceLine, "try")
            "EmptyCatchBlock" -> findKeywordOrDefault(sourceLine, "catch")
            "EmptyFinallyBlock" -> findKeywordOrDefault(sourceLine, "finally")
            "EmptyForStatement" -> findKeywordOrDefault(sourceLine, "for")
            "EmptyWhileStatement" -> findKeywordOrDefault(sourceLine, "while")
            "EmptySwitchStatement" -> findKeywordOrDefault(sourceLine, "switch")
            "EmptySynchronizedStatement" -> findKeywordOrDefault(sourceLine, "synchronized")

            // Groovyism rules
            "GStringExpressionWithinString" -> calculateGStringRange(sourceLine, message)
            "ExplicitCallToEqualsMethod" -> calculateMethodCallRange(sourceLine, "equals")
            "ExplicitCallToCompareToMethod" -> calculateMethodCallRange(sourceLine, "compareTo")
            "GetterMethodCouldBeProperty" -> calculateGetterNameRange(sourceLine, message)

            // Exception rules
            "CatchException" -> calculateExceptionTypeRange(sourceLine, "Exception")
            "CatchThrowable" -> calculateExceptionTypeRange(sourceLine, "Throwable")
            "ThrowException" -> findKeywordOrDefault(sourceLine, "throw")
            "ThrowRuntimeException" -> findKeywordOrDefault(sourceLine, "throw")
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
        findKeywordOrDefault(sourceLine, "public")

    /**
     * Find "def" keyword in the line.
     */
    private fun calculateDefRange(sourceLine: String): Pair<Int, Int> = findKeywordOrDefault(sourceLine, "def")

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
     * Supports both single quotes (CodeNarc's current format: "The String 'value' can be wrapped...")
     * and double quotes (for backward compatibility).
     */
    private fun extractQuotedValue(message: String): String? {
        // Try single quotes first (CodeNarc's current format for UnnecessaryGString)
        val singleQuoteRegex = Regex("""'([^']*)'""")
        singleQuoteRegex.find(message)?.groupValues?.getOrNull(1)?.let { return it }

        // Fall back to double quotes for backward compatibility
        val doubleQuoteRegex = Regex(""""([^"]*)"""")
        return doubleQuoteRegex.find(message)?.groupValues?.getOrNull(1)
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
     * Find a keyword in the source line and return its range, or null if not found.
     */
    private fun findKeywordInLine(sourceLine: String, keyword: String): Pair<Int, Int>? {
        val regex = Regex("""\b${Regex.escape(keyword)}\b""")
        val match = regex.find(sourceLine)
        return if (match != null) {
            Pair(match.range.first, match.range.last + 1)
        } else {
            null
        }
    }

    /**
     * Find a keyword or fall back to default range.
     * This helper reduces duplication and centralizes fallback logic.
     */
    private fun findKeywordOrDefault(sourceLine: String, keyword: String): Pair<Int, Int> =
        findKeywordInLine(sourceLine, keyword) ?: calculateDefaultRange(sourceLine)

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
            // No indentation at all – highlight at least the first character
            Pair(0, 1)
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
     * Calculate range for loop keyword violations (for, while, do-while).
     */
    private fun calculateLoopKeywordRange(sourceLine: String): Pair<Int, Int> = findKeywordInLine(sourceLine, "for")
        ?: findKeywordInLine(sourceLine, "while")
        ?: findKeywordInLine(sourceLine, "do")
        ?: calculateDefaultRange(sourceLine)

    /**
     * Calculate range for try/catch/finally keyword violations.
     * Uses the message to determine which keyword is actually problematic.
     */
    private fun calculateTryCatchFinallyKeywordRange(sourceLine: String, message: String): Pair<Int, Int> {
        // Check message to determine which keyword to highlight
        val keyword = when {
            message.contains("catch", ignoreCase = true) -> "catch"
            message.contains("finally", ignoreCase = true) -> "finally"
            message.contains("try", ignoreCase = true) -> "try"
            else -> null
        }

        // Try to find the specific keyword mentioned in the message
        if (keyword != null) {
            findKeywordInLine(sourceLine, keyword)?.let { return it }
        }

        // Fallback: try all keywords in order
        return findKeywordInLine(sourceLine, "try")
            ?: findKeywordInLine(sourceLine, "catch")
            ?: findKeywordInLine(sourceLine, "finally")
            ?: calculateDefaultRange(sourceLine)
    }

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
            "variable" -> {
                // Variables can be declared with "def" or with a type (e.g., "String badName")
                // Try "def" first, then fall back to last identifier
                findIdentifierAfterKeyword(sourceLine, "def").takeIf {
                    it != calculateDefaultRange(sourceLine)
                } ?: findLastIdentifier(sourceLine)
            }
            "field", "property" -> findLastIdentifier(sourceLine)
            "parameter" -> findLastIdentifier(sourceLine)
            else -> calculateDefaultRange(sourceLine)
        }
    }

    /**
     * Find identifier after a keyword using word-boundary matching.
     * For declarations like "def String myVar", this finds the last identifier (myVar),
     * not the type (String).
     */
    private fun findIdentifierAfterKeyword(sourceLine: String, keyword: String): Pair<Int, Int> {
        // Use word-boundary matching to avoid matching the keyword as a substring of another identifier
        val keywordPattern = Regex("""\b${Regex.escape(keyword)}\b""")
        val keywordMatch = keywordPattern.find(sourceLine)
        if (keywordMatch != null) {
            val afterKeywordStart = keywordMatch.range.last + 1
            val afterKeyword = sourceLine.substring(afterKeywordStart).trimStart()

            // Find all identifiers after the keyword
            val identifierPattern = Regex("""\b[a-zA-Z_]\w*""")
            val identifiers = identifierPattern.findAll(afterKeyword).toList()

            // Use the last identifier (to skip types and get the actual variable/method name)
            val identifierMatch = identifiers.lastOrNull()
            if (identifierMatch != null) {
                val whitespaceOffset = sourceLine.substring(afterKeywordStart).length - afterKeyword.length
                val startIndex = afterKeywordStart + whitespaceOffset + identifierMatch.range.first
                return Pair(startIndex, startIndex + identifierMatch.value.length)
            }
        }
        return calculateDefaultRange(sourceLine)
    }

    /**
     * Find the last identifier in the line (for field/property names).
     *
     * This skips identifiers that are followed (optionally after whitespace) by '(',
     * so that constructor/method calls like "new String()" do not get selected
     * instead of the field name (e.g. in "String field = new String()").
     */
    private fun findLastIdentifier(sourceLine: String): Pair<Int, Int> {
        val identifierMatches = Regex("""\b[a-zA-Z_]\w*\b""").findAll(sourceLine).toList()
        if (identifierMatches.isNotEmpty()) {
            // Iterate from the end to find the last identifier that is not a method/constructor call
            for (i in identifierMatches.indices.reversed()) {
                val match = identifierMatches[i]
                var nextIndex = match.range.last + 1
                // Skip whitespace after the identifier
                while (nextIndex < sourceLine.length && sourceLine[nextIndex].isWhitespace()) {
                    nextIndex++
                }
                // If the next non-whitespace character is '(', this is likely a call, so skip it
                if (nextIndex < sourceLine.length && sourceLine[nextIndex] == '(') {
                    continue
                }
                // Otherwise, treat this identifier as the field/property name
                return Pair(match.range.first, match.range.last + 1)
            }
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
            findIdentifierInLine(sourceLine, methodName)
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
