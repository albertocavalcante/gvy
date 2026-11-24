// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit

/**
 * Applies [edits] to [text]. Fails, listing what each edit would replace, if edits overlap
 * (LSP forbids that) or fall outside the text.
 */
internal fun applyTextEdits(text: String, edits: List<TextEdit>): String {
    val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    fun offset(p: Position) = lineStarts.getOrNull(p.line)?.plus(p.character) ?: Int.MAX_VALUE
    val spans = edits.map { offset(it.range.start) to offset(it.range.end) }.sortedBy { it.first }
    val described = spans.joinToString("; ") { (from, to) ->
        if (to <=
            text.length
        ) {
            "[$from,$to)=" + text.substring(from, to).replace("\n", "\\n")
        } else {
            "[$from,$to)=<out of range>"
        }
    }
    check(spans.all { it.second <= text.length } && spans.zipWithNext().all { (a, b) -> a.second <= b.first }) {
        "Edits overlap or exceed the text: $described"
    }
    return spans.sortedByDescending { it.first }.fold(text) { acc, (from, to) ->
        acc.substring(0, from) + edits.first { offset(it.range.start) == from }.newText + acc.substring(to)
    }
}
