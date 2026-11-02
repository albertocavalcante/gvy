package com.github.albertocavalcante.gvy.gls.utils

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** The name this node declares or refers to as a symbol; null for nodes that have none. */
fun ASTNode.declaredName(): String? = when (this) {
    is VariableExpression -> name
    is Parameter -> name
    is MethodNode -> name
    is FieldNode -> name
    is PropertyNode -> name
    is ClassNode -> nameWithoutPackage.substringAfterLast('$') // nested: Outer\$Inner -> Inner
    else -> null
}

/** Line-start table for one text, built once and reused for many offset/position lookups. */
class TextIndex(val text: String) {
    private val lineStarts: IntArray = ArrayList<Int>().apply {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    /** Offset of [p], clamped to the text. */
    fun offsetOf(p: Position): Int =
        if (p.line >= lineStarts.size) text.length else minOf(lineStarts[p.line] + p.character, text.length)

    /** Position of [offset], found by binary search over the line starts. */
    fun positionOf(offset: Int): Position {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return Position(lo, offset - lineStarts[lo])
    }
}

/**
 * The first whole-word occurrence of [name] inside [range] of the indexed text, as an LSP range;
 * null if there is none. Identifier characters are Java's, which covers Groovy identifiers. A range
 * that runs past the end of the text is clamped to it. Occurrences inside string literals and
 * comments are skipped, so a declaration range that starts at its annotations (`@Json("id") Long id`)
 * resolves to the declared name, not to the annotation's text.
 */
fun firstNameOccurrence(index: TextIndex, range: Range, name: String): Range? {
    if (name.isEmpty()) return null
    val text = index.text
    val end = index.offsetOf(range.end)
    var at = index.offsetOf(range.start)
    while (at < end) {
        val skipTo = skipLiteralOrComment(text, at)
        if (skipTo > at) {
            at = skipTo
        } else if (text.startsWith(name, at) && at + name.length <= end &&
            isWordBoundary(text, at - 1) && isWordBoundary(text, at + name.length)
        ) {
            return Range(index.positionOf(at), index.positionOf(at + name.length))
        } else {
            at++
        }
    }
    return null
}

/** If a string literal or comment starts at [at], the offset just past it; otherwise [at]. */
private fun skipLiteralOrComment(text: String, at: Int): Int {
    val c = text[at]
    return when {
        c == '"' || c == '\'' -> skipString(text, at, c)
        c == '/' && text.startsWith("//", at) -> text.indexOf('\n', at).let { if (it < 0) text.length else it }
        c == '/' && text.startsWith("/*", at) -> text.indexOf("*/", at + 2).let { if (it < 0) text.length else it + 2 }
        else -> at
    }
}

private fun skipString(text: String, at: Int, quote: Char): Int {
    val triple = text.startsWith("$quote$quote$quote", at)
    val closer = if (triple) "$quote$quote$quote" else quote.toString()
    var i = at + closer.length
    while (i < text.length) {
        when {
            text[i] == '\\' -> i += 2
            text.startsWith(closer, i) -> return i + closer.length
            !triple && text[i] == '\n' -> return i
            else -> i++
        }
    }
    return text.length
}

private fun isWordBoundary(text: String, index: Int): Boolean =
    index !in text.indices || !Character.isJavaIdentifierPart(text[index])
