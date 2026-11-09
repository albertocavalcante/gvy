package com.github.albertocavalcante.gvy.gls.utils

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.Parameter
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Resolves parameter spellings from bounded source fragments, not the whole declaration's AST start. */
internal class ParameterSourceRanges private constructor(
    private val index: TextIndex,
    private val lines: List<String>,
) {
    constructor(lines: List<String>) : this(TextIndex(lines.joinToString("\n")), lines)
    constructor(index: TextIndex) : this(index, index.text.split('\n'))
    constructor(source: String) : this(TextIndex(source))

    private val codePointCounts = IntArray(lines.size) { lines[it].codePointCount(0, lines[it].length) }
    data class Span(val line: Int, val start: Int, val length: Int)

    private val order = compareBy<Position> { it.line }.thenBy { it.character }

    fun name(parameter: Parameter): Span? {
        var start = position(parameter, end = false) ?: return null
        var end = position(parameter, end = true) ?: return null
        parameter.initialExpression?.let { expression ->
            position(expression, end = false)?.takeIf { order.compare(it, start) > 0 }?.let {
                if (order.compare(it, end) < 0) end = it
            }
        }
        // Annotation literals may use Groovy's slashy syntax. Exclude their AST spans rather than
        // interpreting them as ordinary quoted strings, including when a dynamic type has no range.
        for (node in parameter.annotations + parameter.type) {
            position(node, end = true)?.let {
                if (order.compare(it, start) > 0 && order.compare(it, end) < 0) start = it
            }
        }
        val fragment = fragment(start, end) ?: return null
        val fragmentIndex = TextIndex(fragment)
        val range = lastNameOccurrence(
            fragmentIndex,
            Range(Position(0, 0), fragmentIndex.positionOf(fragment.length)),
            parameter.name,
        ) ?: return null
        return span(start, fragmentIndex.offsetOf(range.start), parameter.name.length)
    }

    fun type(parameter: Parameter): Span? {
        val start = position(parameter.type, end = false) ?: return null
        val end = position(parameter.type, end = true) ?: return null
        val text = fragment(start, end) ?: return null
        var at = skipTrivia(text, 0)
        var identifier = identifierEnd(text, at) ?: return null
        var lastStart = at
        var lastEnd = identifier
        while (true) {
            val dot = skipTrivia(text, identifier)
            at = if (dot < text.length && text[dot] == '.') skipTrivia(text, dot + 1) else text.length
            identifier = identifierEnd(text, at) ?: break
            lastStart = at
            lastEnd = identifier
        }
        return span(start, lastStart, lastEnd - lastStart)
    }

    private fun position(node: ASTNode, end: Boolean): Position? {
        val line = (if (end) node.lastLineNumber else node.lineNumber) - 1
        val column = (if (end) node.lastColumnNumber else node.columnNumber) - 1
        val count = codePointCounts.getOrNull(line)
        return if (column < 0 || count == null) {
            null
        } else {
            index.groovyOffset(Position(line, column.coerceAtMost(count)))?.let { offset ->
                Position(line, offset - checkNotNull(index.groovyLineStartOffset(line)))
            }
        }
    }

    private fun fragment(start: Position, end: Position): String? =
        if (order.compare(start, end) < 0) index.text.substring(offset(start), offset(end)) else null

    private fun offset(position: Position): Int =
        checkNotNull(index.groovyLineStartOffset(position.line)) + position.character

    private fun span(start: Position, relativeOffset: Int, length: Int): Span {
        val position = index.positionOf(offset(start) + relativeOffset)
        return Span(position.line, position.character, length)
    }

    private fun identifierEnd(text: String, start: Int): Int? = sourceIdentifierEnd(text, start)

    private fun skipTrivia(text: String, start: Int): Int = skipSourceTrivia(text, start)
}
