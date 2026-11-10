package com.github.albertocavalcante.gvy.common.text

/**
 * Utilities for cleaning Groovy code by removing comments and string literals.
 *
 * These functions are useful for heuristic code analysis where you need to avoid false positives
 * from patterns that might appear in comments or strings.
 */
object GroovyCodeCleaner {

    /**
     * Removes single-line comments from a line of code.
     *
     * This is a simple heuristic that handles the most common case: `//` comments.
     * It does not handle strings or multi-line comments.
     *
     * @param line The line to strip comments from
     * @return The line with single-line comments removed
     */
    fun stripSingleLineComments(line: String): String {
        val commentIndex = line.indexOf("//")
        return if (commentIndex != -1) line.substring(0, commentIndex) else line
    }

    /**
     * Removes comments and string literals from Groovy code to avoid false positive detections.
     *
     * This is a heuristic approach that handles common cases but may not be perfect for all edge cases.
     * It handles:
     * - Single-line comments (`//`)
     * - Multi-line comments (`/* */`)
     * - Triple-quoted strings (`'''` and `"""`)
     * - Single-quoted strings (`'...'`)
     * - Double-quoted strings (GStrings) (`"..."`)
     * - Escape sequences in strings
     *
     * NOTE: Heuristic / tradeoff:
     * This function is intentionally complex as it needs to handle multiple Groovy string and comment formats.
     * A proper solution would require a full lexer/parser, but that would defeat the purpose of a lightweight
     * heuristic check. Edge cases like nested strings or complex escape sequences may not be handled perfectly,
     * but this is acceptable for a best-effort detection mechanism that's supplemented by AST-based checks.
     *
     * @param code The code to clean
     * @return The code with comments and string literals removed
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "MagicNumber")
    fun removeCommentsAndStrings(code: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < code.length) {
            when {
                // Single-line comment
                code.startsWith("//", i) -> {
                    i = code.indexOf('\n', i).let { if (it == -1) code.length else it + 1 }
                }
                // Multi-line comment
                code.startsWith("/*", i) -> {
                    i = code.indexOf("*/", i + 2).let { if (it == -1) code.length else it + 2 }
                }
                // Triple-quoted string (GString or regular)
                code.startsWith("'''", i) || code.startsWith("\"\"\"", i) -> {
                    val delimiter = code.substring(i, i + 3)
                    i = code.indexOf(delimiter, i + 3).let { if (it == -1) code.length else it + 3 }
                }
                // Single-quoted string
                code[i] == '\'' -> {
                    i++
                    while (i < code.length) {
                        if (code[i] == '\\') {
                            i += 2 // Skip escaped character
                        } else if (code[i] == '\'') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                }
                // Double-quoted string (GString)
                code[i] == '"' -> {
                    i++
                    while (i < code.length) {
                        if (code[i] == '\\') {
                            i += 2 // Skip escaped character
                        } else if (code[i] == '"') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                }
                // Regular code
                else -> {
                    result.append(code[i])
                    i++
                }
            }
        }
        return result.toString()
    }

    /**
     * Like [removeCommentsAndStrings], but keeps the code's shape: every character of a comment or string literal
     * becomes a space and every newline is kept, so the result has the same length and the same lines as [code].
     * Line-based scanners can then clean a whole prefix once and still read it line by line, with strings and
     * comments that span lines handled (#1281).
     *
     * Also blanks dollar-slashy strings (`$/ ... /$`) and slashy strings (`/ ... /`). A `/` opens a slashy
     * string only where an expression starts (at the start of the code, after `(`, `[`, `{`, `,`, `=`, `:`,
     * `;`, `!`, `&`, `|`, `?`, `~`, `>` (a closure's `->`) or a newline, or after `return`, `case`, `in` or
     * `assert`), so division stays code. `'...'` and `"..."` end at the end of their line even when
     * unterminated, as Groovy does not let them span lines, except inside a `"${...}"` interpolation; triple-quoted,
     * slashy and dollar-slashy strings may span lines.
     */
    fun blankCommentsAndStrings(code: String): String {
        val out = code.toCharArray()
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, code.length)) if (out[k] != '\n') out[k] = ' '
        }
        var i = 0
        while (i < code.length) {
            val end = literalEnd(code, i)
            if (end == null) {
                i++
            } else {
                blank(i, end)
                i = end
            }
        }
        return String(out)
    }

    /** End (exclusive) of the comment or string literal starting at [i], or null when none starts there. */
    private fun literalEnd(code: String, i: Int): Int? = when {
        code.startsWith("//", i) -> code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
        else -> PAIRED_DELIMITERS.firstOrNull { (open, _) -> code.startsWith(open, i) }
            ?.let { (open, close) -> closingEnd(code, close, i + open.length) }
            ?: when {
                // Groovy does not let '...' and "..." span lines: a stray quote must not blank the rest of the file.
                code[i] == '\'' -> quotedEnd(code, i + 1, '\'', stopAtNewline = true)
                code[i] == '"' -> gStringEnd(code, i + 1)
                code[i] == '/' && startsExpression(code, i) -> quotedEnd(code, i + 1, '/', stopAtNewline = false)
                else -> null
            }
    }

    /** Opening and closing delimiters of comments and strings that need no escape handling, longest first. */
    private val PAIRED_DELIMITERS = listOf(
        "/*" to "*/",
        "\'\'\'" to "\'\'\'",
        "\"\"\"" to "\"\"\"",
        "$/" to "/$",
    )

    private fun closingEnd(code: String, delimiter: String, from: Int): Int =
        code.indexOf(delimiter, from).let { if (it < 0) code.length else it + delimiter.length }

    /**
     * End of a literal closed by [quote], with backslash escapes. With [stopAtNewline], an unterminated literal
     * ends before the newline (single-line strings); otherwise it may span lines (slashy strings).
     */
    private fun quotedEnd(code: String, from: Int, quote: Char, stopAtNewline: Boolean): Int {
        var j = from
        while (j < code.length) {
            when {
                code[j] == '\\' -> j += 2
                code[j] == quote -> return j + 1
                stopAtNewline && code[j] == '\n' -> return j
                else -> j++
            }
        }
        return code.length
    }

    /**
     * End of a `"..."` GString starting after its quote. It ends at an unescaped newline like `'...'`, except inside
     * a `${...}` interpolation, whose closures may span lines (`"${ items.collect {\n it } }"`): there braces are
     * counted until the interpolation closes. That multi-line reading is kept only when the closing `"` follows
     * before another line ends; otherwise (a half-typed `"${env.X`) the string ends at its first newline, so it
     * cannot swallow the braces of the lines after it.
     */
    private fun gStringEnd(code: String, from: Int): Int {
        var firstNewline: Int? = null
        var j = from
        while (j < code.length) {
            when {
                code[j] == '\\' -> j += 2
                code.startsWith("\${", j) -> {
                    val (end, newline) = interpolationEnd(code, j + 2)
                    if (firstNewline == null) firstNewline = newline
                    j = end
                }
                code[j] == '"' -> return j + 1
                code[j] == '\n' -> return firstNewline ?: j
                else -> j++
            }
        }
        return firstNewline ?: code.length
    }

    /** End of a `${...}` body starting after its `${`, and the body's first newline when it spans lines. */
    private fun interpolationEnd(code: String, from: Int): Pair<Int, Int?> {
        var depth = 1
        var newline: Int? = null
        var j = from
        while (j < code.length && depth > 0) {
            when (code[j]) {
                '{' -> depth++
                '}' -> depth--
                '\n' -> if (newline == null) newline = j
            }
            j++
        }
        return j to newline
    }

    /** Characters after which a `/` starts a slashy string; `>` covers a closure's `->`. */
    private const val EXPRESSION_STARTERS = "([{,=:;!&|?~>\n"

    /** Keywords after which a `/` starts a slashy string (`case /re/:`, `return /re/`), not a division. */
    private val EXPRESSION_KEYWORDS = setOf("return", "case", "in", "assert")

    private fun startsExpression(code: String, slash: Int): Boolean {
        var j = slash - 1
        while (j >= 0 && (code[j] == ' ' || code[j] == '\t')) j--
        if (j < 0 || code[j] in EXPRESSION_STARTERS) return true
        var wordStart = j
        while (wordStart > 0 && (code[wordStart - 1].isLetterOrDigit() || code[wordStart - 1] == '_')) wordStart--
        return code.substring(wordStart, j + 1) in EXPRESSION_KEYWORDS
    }
}
