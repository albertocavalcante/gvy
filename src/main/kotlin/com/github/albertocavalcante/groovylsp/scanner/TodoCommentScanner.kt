package com.github.albertocavalcante.groovylsp.scanner

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.regex.Pattern

/**
 * Scans source code for TODO, FIXME, and other comment patterns.
 *
 * This scanner follows industry best practices:
 * - Pre-compiled regex patterns for performance
 * - Support for both single-line and multi-line comments
 * - Configurable patterns and severity levels
 * - Zero-indexed line/column positions (LSP standard)
 */
class TodoCommentScanner {

    companion object {
        // Standard TODO patterns recognized across industry tools
        private val DEFAULT_PATTERNS = mapOf(
            "TODO" to DiagnosticSeverity.Information,
            "FIXME" to DiagnosticSeverity.Warning,
            "XXX" to DiagnosticSeverity.Warning,
            "HACK" to DiagnosticSeverity.Hint,
            "NOTE" to DiagnosticSeverity.Information,
            "BUG" to DiagnosticSeverity.Error,
            "OPTIMIZE" to DiagnosticSeverity.Hint,
        )

        // Regex pattern for single-line comments: // TODO: some text
        private fun createSingleLinePattern(keywords: Set<String>): Pattern {
            val keywordGroup = keywords.joinToString("|")
            return Pattern.compile(
                "//\\s*($keywordGroup):?\\s*(.*)",
                Pattern.CASE_INSENSITIVE,
            )
        }

        // Regex pattern for multi-line comments: /* TODO: some text */
        private fun createMultiLinePattern(keywords: Set<String>): Pattern {
            val keywordGroup = keywords.joinToString("|")
            return Pattern.compile(
                "/\\*\\s*($keywordGroup):?\\s*([^*]*(?:\\*(?!/)[^*]*)*)\\*/",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
            )
        }

        // Pattern for finding TODO keywords within multi-line comment blocks
        private fun createInlineMultiLinePattern(keywords: Set<String>): Pattern {
            val keywordGroup = keywords.joinToString("|")
            return Pattern.compile(
                "($keywordGroup):?\\s*(.*?)(?=\\n|\\*/|\$)",
                Pattern.CASE_INSENSITIVE,
            )
        }
    }

    data class TodoItem(
        val keyword: String,
        val description: String,
        val range: Range,
        val severity: DiagnosticSeverity,
    )

    private val patterns: Map<String, DiagnosticSeverity>
    private val singleLinePattern: Pattern
    private val multiLinePattern: Pattern
    private val inlineMultiLinePattern: Pattern

    constructor(customPatterns: Map<String, DiagnosticSeverity>? = null) {
        this.patterns = customPatterns ?: DEFAULT_PATTERNS
        val keywords = this.patterns.keys
        this.singleLinePattern = createSingleLinePattern(keywords)
        this.multiLinePattern = createMultiLinePattern(keywords)
        this.inlineMultiLinePattern = createInlineMultiLinePattern(keywords)
    }

    /**
     * Scans source code text for TODO patterns and returns diagnostic items.
     *
     * @param sourceCode The source code text to scan
     * @param uri The document URI for the diagnostics
     * @return List of diagnostics for found TODO items
     */
    fun scanForTodos(sourceCode: String, uri: String): List<Diagnostic> {
        val todos = mutableListOf<TodoItem>()
        val lines = sourceCode.lines()

        // Scan single-line comments
        scanSingleLineComments(lines, todos)

        // Scan multi-line comments
        scanMultiLineComments(sourceCode, todos)

        // Convert TodoItems to LSP Diagnostics
        return todos.map { todo ->
            Diagnostic().apply {
                range = todo.range
                severity = todo.severity
                source = "todo-scanner"
                message = "${todo.keyword}: ${todo.description.trim()}"
                code = Either.forLeft(todo.keyword.lowercase())
            }
        }
    }

    private fun scanSingleLineComments(lines: List<String>, todos: MutableList<TodoItem>) {
        lines.forEachIndexed { lineIndex, line ->
            val matcher = singleLinePattern.matcher(line)
            if (matcher.find()) {
                val keyword = matcher.group(1).uppercase()
                val description = matcher.group(2) ?: ""
                val startColumn = matcher.start()
                val endColumn = line.length

                val severity = patterns[keyword] ?: DiagnosticSeverity.Information

                todos.add(
                    TodoItem(
                        keyword = keyword,
                        description = description,
                        range = Range(
                            Position(lineIndex, startColumn),
                            Position(lineIndex, endColumn),
                        ),
                        severity = severity,
                    ),
                )
            }
        }
    }

    private fun scanMultiLineComments(sourceCode: String, todos: MutableList<TodoItem>) {
        val matcher = multiLinePattern.matcher(sourceCode)

        while (matcher.find()) {
            val commentStart = matcher.start()
            val commentContent = matcher.group(2) ?: ""

            // Find position in terms of line and column
            val position = getPositionFromOffset(sourceCode, commentStart)

            // Look for TODO patterns within the multi-line comment
            val inlineMatcher = inlineMultiLinePattern.matcher(commentContent)

            while (inlineMatcher.find()) {
                val keyword = inlineMatcher.group(1).uppercase()
                val description = inlineMatcher.group(2) ?: ""
                val severity = patterns[keyword] ?: DiagnosticSeverity.Information

                // Calculate the exact position within the comment
                val keywordStart = matcher.start() + matcher.group(1).length + 2 + inlineMatcher.start()
                val keywordEnd = matcher.start() + matcher.group(1).length + 2 + inlineMatcher.end()

                val startPos = getPositionFromOffset(sourceCode, keywordStart)
                val endPos = getPositionFromOffset(sourceCode, keywordEnd)

                todos.add(
                    TodoItem(
                        keyword = keyword,
                        description = description,
                        range = Range(startPos, endPos),
                        severity = severity,
                    ),
                )
            }
        }
    }

    private fun getPositionFromOffset(text: String, offset: Int): Position {
        val lines = text.substring(0, offset).lines()
        val line = lines.size - 1
        val character = if (lines.isEmpty()) 0 else lines.last().length
        return Position(line, character)
    }

    /**
     * Gets the supported TODO patterns and their severity levels.
     */
    fun getSupportedPatterns(): Map<String, DiagnosticSeverity> = patterns.toMap()

    /**
     * Checks if a keyword is a recognized TODO pattern.
     */
    fun isRecognizedPattern(keyword: String): Boolean = patterns.containsKey(keyword.uppercase())
}
