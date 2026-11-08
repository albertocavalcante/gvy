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

    /** Groovy lexer columns count code points; LSP columns count UTF-16 code units. */
    internal fun utf16Position(codePoints: Position): Position? {
        val line = lineText(codePoints.line) ?: return null
        if (codePoints.character !in 0..line.codePointCount(0, line.length)) return null
        return Position(codePoints.line, line.offsetByCodePoints(0, codePoints.character))
    }

    internal fun utf16Range(codePoints: Range): Range? {
        val start = utf16Position(codePoints.start) ?: return null
        val end = utf16Position(codePoints.end) ?: return null
        return Range(start, end)
    }

    internal fun codePointPosition(utf16: Position): Position? {
        val line = lineText(utf16.line) ?: return null
        if (utf16.character !in 0..line.length) return null
        return Position(utf16.line, line.codePointCount(0, utf16.character))
    }

    private fun lineText(line: Int): String? {
        val start = lineStarts.getOrNull(line) ?: return null
        val end = lineStarts.getOrNull(line + 1)?.minus(1) ?: text.length
        return text.substring(start, end).removeSuffix("\r")
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
    // Slashy strings (/…/, $/…/$) are not recognised, so a quote inside one can hide the rest of the
    // line; fall back to a plain search rather than lose a declaration that is really there.
    return findName(index, range, name, skipLiterals = true) ?: findName(index, range, name, skipLiterals = false)
}

/** Last whole identifier, ignoring comments and quoted strings without a plain-text fallback. */
internal fun lastNameOccurrence(index: TextIndex, range: Range, name: String): Range? =
    if (name.isEmpty()) null else findName(index, range, name, skipLiterals = true, last = true)

private fun findName(
    index: TextIndex,
    range: Range,
    name: String,
    skipLiterals: Boolean,
    last: Boolean = false,
): Range? {
    val text = index.text
    val end = index.offsetOf(range.end)
    var at = index.offsetOf(range.start)
    var found: Range? = null
    while (at < end) {
        val skipTo = if (skipLiterals) skipLiteralOrComment(text, at) else at
        if (skipTo > at) {
            at = skipTo
        } else if (text.startsWith(name, at) && at + name.length <= end &&
            isWordBoundary(text, at - 1) && isWordBoundary(text, at + name.length)
        ) {
            found = Range(index.positionOf(at), index.positionOf(at + name.length))
            if (!last) return found
            at += name.length
        } else {
            at++
        }
    }
    return found
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
