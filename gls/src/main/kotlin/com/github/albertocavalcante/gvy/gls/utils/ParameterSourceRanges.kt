package com.github.albertocavalcante.gvy.gls.utils

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.Parameter
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Resolves parameter spellings from bounded source fragments, not the whole declaration's AST start. */
internal class ParameterSourceRanges(private val lines: List<String>) {
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
        val index = TextIndex(fragment)
        val range = lastNameOccurrence(
            index,
            Range(Position(0, 0), index.positionOf(fragment.length)),
            parameter.name,
        ) ?: return null
        return span(start, range.start, parameter.name.length)
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
        return span(start, TextIndex(text).positionOf(lastStart), lastEnd - lastStart)
    }

    private fun position(node: ASTNode, end: Boolean): Position? {
        val line = (if (end) node.lastLineNumber else node.lineNumber) - 1
        val column = (if (end) node.lastColumnNumber else node.columnNumber) - 1
        val text = lines.getOrNull(line) ?: return null
        if (column < 0) return null
        // Groovy's lexer columns count code points; semantic token columns count UTF-16 code units.
        return Position(line, text.offsetByCodePoints(0, column.coerceAtMost(text.codePointCount(0, text.length))))
    }

    private fun fragment(start: Position, end: Position): String? {
        if (order.compare(start, end) >= 0) return null
        return (start.line..end.line).joinToString("\n") { line ->
            val text = lines[line]
            val from = if (line == start.line) start.character else 0
            val to = if (line == end.line) end.character else text.length
            text.substring(from, to)
        }
    }

    private fun span(start: Position, relative: Position, length: Int): Span = Span(
        start.line + relative.line,
        relative.character + if (relative.line == 0) start.character else 0,
        length,
    )

    private fun identifierEnd(text: String, start: Int): Int? {
        if (start >= text.length || !Character.isJavaIdentifierStart(text.codePointAt(start))) return null
        var at = start
        while (at < text.length && Character.isJavaIdentifierPart(text.codePointAt(at))) {
            at += Character.charCount(text.codePointAt(at))
        }
        return at
    }

    private fun skipTrivia(text: String, start: Int): Int {
        var at = start
        while (at < text.length) {
            at = when {
                text[at].isWhitespace() -> at + 1
                text.startsWith("/*", at) -> commentEnd(text, at, "*/")
                text.startsWith("//", at) -> commentEnd(text, at, "\n")
                else -> return at
            }
        }
        return at
    }

    private fun commentEnd(text: String, start: Int, closer: String): Int {
        val end = text.indexOf(closer, start + 2)
        return if (end < 0) text.length else end + if (closer == "\n") 0 else closer.length
    }
}
