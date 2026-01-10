package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/**
 * Abstract base class for diagnostic rules providing common utilities.
 *
 * Subclasses only need to implement analyzeImpl() with their specific logic.
 * This base class handles error recovery and provides helper methods for
 * creating diagnostics.
 */
abstract class AbstractDiagnosticRule : DiagnosticRule {

    override suspend fun analyze(uri: URI, content: String, context: RuleContext): List<Diagnostic> {
        // Skip analysis if there are syntax errors (unless rule explicitly allows it)
        if (context.hasErrors() && !allowsErroredCode()) {
            return emptyList()
        }

        return runCatching { analyzeImpl(uri, content, context) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                // Log error but don't propagate - rules should be isolated
                org.slf4j.LoggerFactory.getLogger(javaClass).error("Rule $id failed", error)
            }
            .getOrElse { emptyList() }
    }

    /**
     * Implement this method with rule-specific analysis logic.
     */
    protected abstract suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext): List<Diagnostic>

    /**
     * Whether this rule should run even when there are syntax errors.
     * Default is false - most rules should skip errored code.
     */
    protected open fun allowsErroredCode(): Boolean = false

    /**
     * Create a diagnostic at the specified position.
     * Automatically adds analysis type prefix to the code for clarity.
     */
    protected fun diagnostic(
        range: Range,
        message: String,
        severity: DiagnosticSeverity = defaultSeverity,
        code: String? = formatDiagnosticCode(id),
    ): Diagnostic = Diagnostic(range, message, severity, "groovy-lsp", code)

    /**
     * Convenience for a single-line range.
     */
    protected fun diagnostic(
        line: Int,
        startChar: Int,
        endChar: Int,
        message: String,
        severity: DiagnosticSeverity = defaultSeverity,
    ): Diagnostic = diagnostic(
        range = Range(Position(line, startChar), Position(line, endChar)),
        message = message,
        severity = severity,
    )

    /**
     * Format diagnostic code with analysis type prefix.
     * Examples: "H:println-debug", "A:unused-import", "S:type-mismatch"
     */
    private fun formatDiagnosticCode(ruleId: String): String {
        val prefix = when (analysisType) {
            DiagnosticAnalysisType.AST -> "A"
            DiagnosticAnalysisType.HEURISTIC -> "H"
            DiagnosticAnalysisType.SEMANTIC -> "S"
        }
        return "$prefix:$ruleId"
    }

    /**
     * Find all regex matches in content, excluding those in comments and optionally strings.
     *
     * Note: This is a line-based heuristic that handles single-line comments (//) but not
     * multi-line block comments (/* ... */). For more accurate comment detection, consider
     * using AST-based analysis.
     *
     * @param content The source code content to search
     * @param pattern The regex pattern to match
     * @param excludeComments Whether to skip matches inside single-line comments (default: true)
     * @param excludeStrings Whether to skip matches inside string literals (default: false)
     * @return Sequence of LineMatch objects containing match details
     */
    protected fun findPatternMatches(
        content: String,
        pattern: Regex,
        excludeComments: Boolean = true,
        excludeStrings: Boolean = false,
    ): Sequence<LineMatch> = sequence {
        val lines = content.lines()

        for ((lineIndex, line) in lines.withIndex()) {
            // Skip comment-only lines if requested (heuristic for common comment patterns)
            if (excludeComments) {
                val trimmed = line.trimStart()
                // Only skip if the line starts with comment markers AND has no code after
                // This is a heuristic - it won't catch all cases (e.g., /* comment */ code)
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue
                }
            }

            var searchFrom = 0
            while (true) {
                val match = pattern.find(line, searchFrom) ?: break

                var shouldExclude = false

                // Check if match is in a single-line comment
                // Need to find the first // that's not inside a string (handles URLs in strings)
                if (excludeComments) {
                    var commentIndex = -1
                    var searchIndex = 0
                    while (true) {
                        val foundIndex = line.indexOf("//", searchIndex)
                        if (foundIndex == -1) break
                        if (!isInString(line, foundIndex)) {
                            commentIndex = foundIndex
                            break
                        }
                        searchIndex = foundIndex + 1
                    }
                    if (commentIndex != -1 && match.range.first > commentIndex) {
                        shouldExclude = true
                    }
                }

                // Check if match is in a string literal
                if (!shouldExclude && excludeStrings && isInString(line, match.range.first)) {
                    shouldExclude = true
                }

                if (!shouldExclude) {
                    yield(LineMatch(lineIndex, match, line))
                }

                searchFrom = match.range.last + 1
            }
        }
    }

    /**
     * Create diagnostics from pattern matches with a custom message provider.
     *
     * @param content The source code content to search
     * @param pattern The regex pattern to match
     * @param messageProvider Function to generate diagnostic message from a match
     * @param severity The diagnostic severity level (default: rule's defaultSeverity)
     * @param excludeComments Whether to skip matches inside comments (default: true)
     * @param excludeStrings Whether to skip matches inside string literals (default: false)
     * @return List of diagnostics for all matches
     */
    protected fun diagnosticsFromPattern(
        content: String,
        pattern: Regex,
        messageProvider: (LineMatch) -> String,
        severity: DiagnosticSeverity = defaultSeverity,
        excludeComments: Boolean = true,
        excludeStrings: Boolean = false,
    ): List<Diagnostic> = findPatternMatches(content, pattern, excludeComments, excludeStrings)
        .map { lineMatch ->
            diagnostic(
                lineMatch.lineIndex,
                lineMatch.match.range.first,
                lineMatch.match.range.last + 1,
                messageProvider(lineMatch),
                severity,
            )
        }
        .toList()

    /**
     * Check if a position in a line is inside a string literal.
     * Handles both single and double quotes, and respects escape sequences.
     *
     * Note: This is a simplified implementation that only handles basic string literals
     * with single (') and double (") quotes. It does not handle Groovy's triple-quoted
     * strings (''' or """), slashy strings (/.../), or dollar-slashy strings ($/.../$).
     * For more accurate string detection, consider using AST-based analysis.
     *
     * @param line The line to check
     * @param position The character position to check
     * @return true if the position is inside a string literal
     */
    protected fun isInString(line: String, position: Int): Boolean {
        var inString = false
        var stringChar: Char? = null

        for (i in 0 until position.coerceAtMost(line.length)) {
            val char = line[i]
            if (isQuote(char)) {
                if (!inString) {
                    inString = true
                    stringChar = char
                } else if (char == stringChar && !isEscaped(line, i)) {
                    inString = false
                    stringChar = null
                }
            }
        }

        return inString
    }

    /**
     * Check if a character at a given index is escaped with a backslash.
     * Handles multiple consecutive backslashes correctly.
     *
     * @param line The line containing the character
     * @param index The index of the character to check
     * @return true if the character is escaped (odd number of preceding backslashes)
     */
    protected fun isEscaped(line: String, index: Int): Boolean {
        var backslashCount = 0
        var currentIndex = index - 1
        while (currentIndex >= 0 && line[currentIndex] == '\\') {
            backslashCount++
            currentIndex--
        }
        return backslashCount % 2 == 1
    }

    /**
     * Check if a character is a quote character (single or double quote).
     */
    private fun isQuote(char: Char): Boolean = char == '"' || char == '\''

    /**
     * Data class representing a regex match on a specific line.
     *
     * @property lineIndex The zero-based line index where the match occurred
     * @property match The regex MatchResult containing match details
     * @property line The full line text where the match occurred
     */
    data class LineMatch(val lineIndex: Int, val match: MatchResult, val line: String)
}
