package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.eclipse.lsp4j.Range

/**
 * Select the last identifier in a compiler-bounded qualified type head. Generic arguments,
 * constructor arguments and `.class` belong to other references. [names] contains only the
 * canonical name and aliases proved by the matching compilation; this scanner does not resolve types.
 */
internal fun typeReferenceRange(index: TextIndex, range: Range, names: Set<String>): Range? =
    TypeHeadReader(index, range).read()?.takeIf {
        index.text.substring(index.offsetOf(it.start), index.offsetOf(it.end)) in names
    }

private class TypeHeadReader(private val index: TextIndex, range: Range) {
    private val text = index.text
    private val end = index.offsetOf(range.end)
    private var at = index.offsetOf(range.start)

    fun read(): Range? {
        var head = identifier() ?: return null
        while (consumeDot()) {
            val next = identifier() ?: return null
            if (text.substring(index.offsetOf(next.start), index.offsetOf(next.end)) == "class") return head
            head = next
        }
        return head
    }

    private fun identifier(): Range? {
        skipTrivia()
        if (at >= end || !Character.isJavaIdentifierStart(text.codePointAt(at))) return null
        val start = at
        at += Character.charCount(text.codePointAt(at))
        while (at < end && Character.isJavaIdentifierPart(text.codePointAt(at))) {
            at += Character.charCount(text.codePointAt(at))
        }
        return Range(index.positionOf(start), index.positionOf(at))
    }

    private fun consumeDot(): Boolean {
        skipTrivia()
        if (at >= end || text[at] != '.') return false
        at++
        return true
    }

    private fun skipTrivia() {
        var next = if (at < end) skipTypeTrivia(text, at, end) else at
        while (next > at) {
            at = next
            next = if (at < end) skipTypeTrivia(text, at, end) else at
        }
    }
}

private fun skipTypeTrivia(text: String, at: Int, end: Int): Int = when {
    text[at].isWhitespace() -> at + 1
    text.startsWith("/*", at) -> text.indexOf("*/", at + 2).let { if (it < 0) end else minOf(it + 2, end) }
    text.startsWith("//", at) -> text.indexOf('\n', at + 2).let { if (it < 0) end else minOf(it + 1, end) }
    else -> at
}
