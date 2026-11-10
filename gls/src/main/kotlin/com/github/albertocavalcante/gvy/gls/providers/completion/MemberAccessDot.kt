package com.github.albertocavalcante.gvy.gls.providers.completion

/**
 * True when the text before the cursor (ignoring comments, whitespace and the identifier being typed,
 * possibly across lines) ends with a `.`, i.e. there is a member-access dot to complete after.
 */
internal fun followsMemberAccessDot(content: String, line: Int, character: Int): Boolean {
    val lines = content.lines()
    if (line !in lines.indices) return false
    val before = lines.take(line).joinToString("\n") + (if (line > 0) "\n" else "") +
        lines[line].take(character.coerceIn(0, lines[line].length))
    return GroovyCodeScanner.stripComments(before)
        .trimEnd { it.isLetterOrDigit() || it == '_' || it == '$' }
        .trimEnd()
        .endsWith('.')
}
