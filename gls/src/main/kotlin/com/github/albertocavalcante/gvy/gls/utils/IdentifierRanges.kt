// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import com.github.albertocavalcante.gvy.semantics.sourceSimpleName
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.concurrent.ConcurrentHashMap

/** The name this node declares or refers to as a symbol; null for nodes that have none. */
fun ASTNode.declaredName(): String? = when (this) {
    is VariableExpression -> name
    is Parameter -> name
    is MethodNode -> name
    is FieldNode -> name
    is PropertyNode -> name
    is ClassNode -> sourceSimpleName()
    else -> null
}

/** Line-start table for one text, built once and reused for many offset/position lookups. */
class TextIndex(val text: String) {
    private val protocolIndex = Utf16LineIndex(text)
    private val groovyLineStarts = calculateGroovyLineStarts()
    private val groovyColumns = ConcurrentHashMap<Int, IntArray>()

    /** Offset of [p], clamped to the text. */
    fun offsetOf(p: Position): Int = protocolIndex.offsetOf(p.line, p.character)

    /** Position of [offset], found by binary search over the protocol line starts. */
    fun positionOf(offset: Int): Position {
        val line = protocolIndex.lineAt(offset)
        return Position(line, offset - protocolIndex.lineStart(line))
    }

    /** Groovy counts code points and advances lines on LF; LSP uses UTF-16 and also recognizes bare CR. */
    internal fun utf16Position(codePoints: Position): Position? {
        val column = groovyColumnOffsets(codePoints.line)?.getOrNull(codePoints.character) ?: return null
        val position = positionOf(groovyLineStarts[codePoints.line] + column)
        return position.takeIf { isValidPosition(it) }
    }

    internal fun utf16Range(codePoints: Range): Range? {
        val start = utf16Position(codePoints.start) ?: return null
        val end = utf16Position(codePoints.end) ?: return null
        return Range(start, end)
    }

    internal fun codePointPosition(utf16: Position): Position? {
        if (!isValidPosition(utf16)) return null
        val offset = offsetOf(utf16)
        val line = lineAt(offset, groovyLineStarts)
        val start = groovyLineStarts[line]
        val columns = groovyColumns[line]
        val column = if (columns == null) {
            // A single cursor lookup needs no full-line map allocation.
            text.codePointCount(start, offset)
        } else {
            val found = columns.binarySearch(offset - start)
            if (found >= 0) found else -found - 1
        }
        return Position(line, column)
    }

    internal fun isValidPosition(position: Position): Boolean =
        protocolIndex.isValidPosition(position.line, position.character)

    internal fun lineRange(line: Int): Range? =
        protocolIndex.lineLength(line)?.let { Range(Position(line, 0), Position(line, it)) }

    /** Absolute UTF-16 offset in Groovy's LF-only coordinate system, including line-break interiors. */
    internal fun groovyOffset(position: Position): Int? {
        val column = groovyColumnOffsets(position.line)?.getOrNull(position.character) ?: return null
        return groovyLineStarts[position.line] + column
    }

    internal fun groovyLineStartOffset(line: Int): Int? = groovyLineStarts.getOrNull(line)

    private fun groovyColumnOffsets(line: Int): IntArray? {
        val start = groovyLineStarts.getOrNull(line) ?: return null
        return groovyColumns.computeIfAbsent(line) {
            val end = groovyLineStarts.getOrNull(line + 1)?.minus(1) ?: text.length
            val columns = IntArray(text.codePointCount(start, end) + 1)
            var at = start
            var column = 0
            while (at < end) {
                columns[column++] = at - start
                at += Character.charCount(text.codePointAt(at))
            }
            columns[column] = end - start
            columns
        }
    }

    private fun calculateGroovyLineStarts(): IntArray {
        val starts = arrayListOf(0)
        for (at in text.indices) {
            if (text[at] == '\n') starts += at + 1
        }
        return starts.toIntArray()
    }

    private fun lineAt(offset: Int, starts: IntArray): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
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

private fun isWordBoundary(text: String, index: Int): Boolean {
    if (index !in text.indices) return true
    val start = if (index > 0 && Character.isLowSurrogate(text[index]) && Character.isHighSurrogate(text[index - 1])) {
        index - 1
    } else {
        index
    }
    return !Character.isJavaIdentifierPart(text.codePointAt(start))
}
