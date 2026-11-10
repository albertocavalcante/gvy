package com.github.albertocavalcante.gvy.gls.providers.completion

/**
 * Minimal single-pass lexer that separates Groovy code from string literals and comments. It is a
 * heuristic for text that may not parse (the cursor is usually mid-edit): it understands quotes,
 * triple quotes, escapes, `//` and block comments, but not slashy or dollar-slashy strings.
 */
internal object GroovyCodeScanner {
    /** True when the text before [position] ends inside an unterminated string literal. */
    fun isInsideString(text: String, position: Int): Boolean {
        if (text.isEmpty() || position <= 0) return false
        return scan(text.substring(0, position.coerceIn(0, text.length)), StringBuilder())
    }

    /** [text] without comments; strings are left intact, so `"http://x"` is not mistaken for a comment. */
    fun stripComments(text: String): String = StringBuilder().also { scan(text, it) }.toString()

    private class Step(val next: Int, val delimiter: String?)

    private fun scan(text: String, code: StringBuilder): Boolean {
        var delimiter: String? = null
        var i = 0
        while (i < text.length) {
            val open = delimiter
            val step = if (open == null) codeStep(text, i, code) else stringStep(text, i, open, code)
            delimiter = step.delimiter
            i = step.next
        }
        return delimiter != null
    }

    private fun codeStep(text: String, i: Int, code: StringBuilder): Step = when {
        text.startsWith("//", i) -> Step(text.indexOf('\n', i).let { if (it < 0) text.length else it }, null)
        text.startsWith("/*", i) -> Step(text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }, null)
        else -> {
            val delimiter = stringDelimiterAt(text, i)
            val length = delimiter?.length ?: 1
            code.append(text, i, i + length)
            Step(i + length, delimiter)
        }
    }

    private fun stringStep(text: String, i: Int, open: String, code: StringBuilder): Step = when {
        text[i] == '\\' -> {
            code.append(text, i, minOf(i + 2, text.length))
            Step(i + 2, open)
        }

        text.startsWith(open, i) -> {
            code.append(open)
            Step(i + open.length, null)
        }

        else -> {
            code.append(text[i])
            Step(i + 1, open)
        }
    }

    private fun stringDelimiterAt(text: String, i: Int): String? = when {
        text.startsWith("'''", i) -> "'''"
        text.startsWith("\"\"\"", i) -> "\"\"\""
        text[i] == '\'' -> "'"
        text[i] == '"' -> "\""
        else -> null
    }
}
