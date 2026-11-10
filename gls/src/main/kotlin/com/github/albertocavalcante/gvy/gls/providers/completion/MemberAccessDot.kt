package com.github.albertocavalcante.gvy.gls.providers.completion

/**
 * How many lines before the cursor are lexed. The lexer does not understand slashy strings, so an odd
 * quote in one (`/it's/`) would otherwise swallow the rest of the file; bounding the window bounds the damage.
 */
private const val LOOKBACK_LINES = 10

/**
 * True when the text before the cursor (ignoring comments, whitespace and the identifier being typed,
 * possibly across lines) ends with a member-access operator: `.`, `?.`, `*.`, `.@` or `.&`.
 */
internal fun followsMemberAccessDot(content: String, line: Int, character: Int): Boolean {
    val lines = content.lines()
    if (line !in lines.indices) return false
    val window = lines.subList((line - LOOKBACK_LINES).coerceAtLeast(0), line)
    val before = window.joinToString("\n") + (if (window.isNotEmpty()) "\n" else "") +
        lines[line].take(character.coerceIn(0, lines[line].length))
    val code = GroovyCodeScanner.stripComments(before)
        .trimEnd { it.isLetterOrDigit() || it == '_' || it == '$' }
        .trimEnd()
    return code.endsWith('.') || code.endsWith(".@") || code.endsWith(".&")
}
